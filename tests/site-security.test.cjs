"use strict";
const assert=require("node:assert/strict"),fs=require("node:fs"),path=require("node:path"),test=require("node:test"),vm=require("node:vm");
const root=path.resolve(__dirname,".."),scripts=["validate.js","app.js"].map(file=>fs.readFileSync(path.join(root,"site","dist",file),"utf8"));
const food={op:"upsert",id:"meal-one",name:"Суп 🍲",kcal:125.5,time:"2020-01-15T12:34:56+03:00"};
const envelope=(...items)=>JSON.stringify({items});
const link=json=>"https://food.kukakur.ru/#"+Buffer.from(json).toString("base64url");
function runtime(url) {
  const texts=[],listeners=new Map(),nodes={preview:{hidden:true,append(){},replaceChildren(){texts.length=0;}},error:{hidden:true}};
  const context=vm.createContext({
    location:{hash:url.includes("#")?url.slice(url.indexOf("#")):"",href:url},
    atob:s=>Buffer.from(s,"base64").toString("binary"),btoa:s=>Buffer.from(s,"binary").toString("base64"),TextEncoder,TextDecoder,Uint8Array,
    fetch(){throw Error("Unexpected network request");},addEventListener(name,fn){listeners.set(name,fn);},
    document:{getElementById:id=>nodes[id],createElement:()=>({append(){},set textContent(text){texts.push(String(text));},set innerHTML(_){throw Error("Unsafe HTML insertion");}})}
  });
  scripts.forEach(script=>vm.runInContext(script,context,{timeout:1000}));
  return {context,texts,get preview(){return !nodes.preview.hidden;},get error(){return !nodes.error.hidden;},change(url){context.location.href=url;context.location.hash=url.includes("#")?url.slice(url.indexOf("#")):"";listeners.get("hashchange")();}};
}
const vectors=JSON.parse(fs.readFileSync(path.join(__dirname,"vectors","deep-links.json"),"utf8"));
for(const vector of vectors) test("shared parser vector: "+vector.label,()=>{
  const state=runtime(vector.url);
  let accepted=false,request;
  try { request=state.context.parseRequestLink(vector.url);accepted=true; } catch {}
  assert.equal(accepted,vector.valid);
  if(vector.valid) {
    assert.equal(state.preview,true);assert.equal(state.error,false);
    const actual=Array.from(request.items,item=>({op:item.op,id:item.id,rev:item.rev,...(item.op==="upsert"?{name:item.name}:{})}));
    assert.deepEqual(actual,vector.expectedOperations);
  } else assert.equal(state.preview,false);
});
test("all invalid batch items fail before any preview is rendered",()=>{
  const state=runtime(link(envelope(food,{...food,id:"second",kcal:-1})));
  assert.equal(state.preview,false);assert.equal(state.error,true);assert.deepEqual(state.texts,[]);
});
test("renders names and IDs as text, including HTML-looking input",()=>{
  const name='<img src=x onerror=alert(1)>',id='<script>alert(1)</script>';
  const state=runtime(link(envelope({...food,name,id},{op:"delete",id:"<b>delete-id</b>",rev:2})));
  assert.equal(state.preview,true);assert.ok(state.texts.includes(name));assert.ok(state.texts.some(t=>t.includes(id)));
  assert.ok(state.texts.some(t=>t.includes("ручного подтверждения")));assert.ok(state.texts.some(t=>t.includes("<b>delete-id</b>")));
});
test("shows mixed operation order and explicit partial batch semantics",()=>{
  const state=runtime(link(envelope(food,{op:"delete",id:"meal-two",rev:2})));
  assert.ok(state.texts.indexOf(food.name)<state.texts.indexOf("2. Удалить блюдо"));
  assert.ok(state.texts.some(t=>t.includes("уже выполненные действия не отменяются")));
});
test("shows suspicious and future-time warnings without performing requests",()=>{
  const state=runtime(link(envelope({...food,kcal:5000,time:"2100-01-01T00:00:00Z"})));
  assert.equal(state.preview,true);assert.ok(state.texts.some(t=>t.includes("Необычно большие")));assert.ok(state.texts.some(t=>t.includes("ещё не наступило")));
});
test("missing and zero nutrients remain distinct, wall clock offset remains visible",()=>{
  const state=runtime(link(envelope({...food,p:0})));
  assert.ok(state.texts.includes("0 g"));assert.ok(state.texts.includes("Белки"));assert.ok(!state.texts.includes("Жиры"));
  assert.ok(state.texts.some(t=>t.includes("2020-01-15 12:34:56+03:00")));
});
test("hash navigation clears previous operations, delete warning and errors",()=>{
  const state=runtime(link(envelope({op:"delete",id:"deleted",rev:2})));
  state.change(link(envelope(food)));
  assert.equal(state.preview,true);assert.ok(!state.texts.some(t=>t.includes("Удалений:")));
  state.change("https://food.kukakur.ru/#invalid");assert.equal(state.error,true);assert.equal(state.preview,false);assert.deepEqual(state.texts,[]);
  state.change(link(envelope(food)));assert.equal(state.error,false);assert.equal(state.preview,true);
  state.change("https://food.kukakur.ru/");assert.equal(state.error,false);assert.equal(state.preview,false);assert.deepEqual(state.texts,[]);
});
test("UTF-8 BOM is not silently stripped into accepted JSON",()=>{
  const state=runtime(link("\ufeff"+envelope(food)));assert.equal(state.error,true);assert.equal(state.preview,false);
});
test("every published example uses the universal parser",()=>{
  const examples=path.join(root,"tests","vectors");
  for(const file of fs.readdirSync(examples).filter(name=>name.startsWith("example-") && name.endsWith("-payload.json"))) {
    const json=fs.readFileSync(path.join(examples,file),"utf8"),state=runtime(link(json));
    assert.equal(state.preview,true,file);assert.equal(state.error,false,file);
  }
});

test("rejects trailing line terminators instead of accepting regex end-of-line",()=>{
  const state=runtime(link(envelope(food)));
  for(const suffix of ["\n","\r","\r\n","\u2028","\u2029"]) assert.throws(()=>state.context.parseRequestLink(link(envelope(food))+suffix));
});
