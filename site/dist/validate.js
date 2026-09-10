"use strict";
// Strict single-envelope parser. Number lexemes are retained for exact revision checks.
function validateRequest(text) {
  if (typeof text !== "string" || new TextEncoder().encode(text).length > 8192) throw Error("Payload too large");
  let i = 0;
  const numbers = new WeakMap();
  const ws = () => { while (i < text.length && " \r\n\t".includes(text[i])) i++; };
  const take = expected => { ws(); if (text[i++] !== expected) throw Error("Invalid JSON"); };
  function string() {
    const start = i;
    take('"');
    while (i < text.length) {
      const c = text[i++];
      if (i - start > 1202 || c.charCodeAt(0) < 32) throw Error("Invalid string");
      if (c === '"') return JSON.parse(text.slice(start, i));
      if (c === "\\") {
        const escape = text[i++];
        if (escape === "u") { for (let j=0;j<4;j++) if (!/[0-9a-fA-F]/.test(text[i++] || "!")) throw Error("Invalid escape"); }
        else if (!['"', "\\", "/", "b", "f", "n", "r", "t"].includes(escape)) throw Error("Invalid escape");
      }
    }
    throw Error("Unterminated string");
  }
  function value(depth) {
    ws();
    if (text[i] === '"') return string();
    if (text[i] === "{" || text[i] === "[") {
      if (depth >= 3) throw Error("Nested data");
      const array = text[i++] === "[", out = array ? [] : Object.create(null);
      const raw = Object.create(null);
      if (!array) numbers.set(out, raw);
      ws();
      const end = array ? "]" : "}";
      if (text[i] === end) { i++; return out; }
      while (true) {
        let key;
        if (array) { if (out.length >= 20) throw Error("Too many items"); }
        else {
          ws(); if (text[i] !== '"') throw Error("Invalid key");
          key = string();
          if (Object.hasOwn(out, key)) throw Error("Duplicate key");
          take(":");
        }
        ws(); const start = i, child = value(depth + 1);
        if (array) out.push(child);
        else { out[key] = child; if (typeof child === "number") raw[key] = text.slice(start, i); }
        ws(); if (text[i] === end) { i++; return out; }
        take(",");
      }
    }
    const start = i;
    while (i < text.length && !",]}: \r\n\t".includes(text[i])) i++;
    const raw = text.slice(start, i);
    if (raw.length > 64 || !/^(?:-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?|true|false|null)$/.test(raw)) throw Error("Invalid scalar");
    return JSON.parse(raw);
  }
  const root = value(0); ws();
  if (i !== text.length || !root || Array.isArray(root) || Object.keys(root).length !== 1 || !Object.hasOwn(root,"items") || !Array.isArray(root.items) || root.items.length < 1 || root.items.length > 20) throw Error("Expected items");
  const seen = new Set();
  const stringField = (item, key, max, required=false) => {
    if (!Object.hasOwn(item,key)) { if (required) throw Error("Missing field"); return; }
    const v = item[key];
    if (typeof v !== "string" || !v.trim() || v.length > max || /[\u0000-\u001f\u007f-\u009f\u202a-\u202e\u2066-\u2069]/u.test(v) || /[\uD800-\uDFFF]/u.test(v)) throw Error("Invalid text");
    return v;
  };
  const nutrients = {kcal:20000,p:5000,f:5000,c:5000,fiber:5000,sugar:5000,saturatedFat:5000,sodiumMg:100000};
  const upsertFields = new Set(["op","id","rev","name","time","meal",...Object.keys(nutrients)]);
  for (const item of root.items) {
    if (!item || Array.isArray(item) || typeof item !== "object" || !["upsert","delete"].includes(item.op)) throw Error("Invalid operation");
    const allowed = item.op === "delete" ? new Set(["op","id","rev"]) : upsertFields;
    if (Object.keys(item).some(key=>!allowed.has(key))) throw Error("Unknown field");
    const id = stringField(item,"id",200,true);
    if (seen.has(id)) throw Error("Duplicate ID"); seen.add(id);
    if (Object.hasOwn(item,"rev")) {
      if (typeof item.rev !== "number" || !/^[1-9][0-9]{0,6}$/.test(numbers.get(item).rev || "") || item.rev > 1000000) throw Error("Invalid revision");
    } else if (item.op === "delete") throw Error("Missing revision");
    else item.rev = 1;
    if (item.op === "delete") { if (item.rev < 2) throw Error("Invalid delete revision"); continue; }
    stringField(item,"name",200,true);
    if (!Object.hasOwn(item,"kcal")) throw Error("Missing energy");
    for (const [key,max] of Object.entries(nutrients)) if (Object.hasOwn(item,key) && (typeof item[key] !== "number" || !Number.isFinite(item[key]) || item[key]<0 || item[key]>max)) throw Error("Invalid nutrient");
    const time = stringField(item,"time",40,true);
    const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|([+-])(\d{2}):(\d{2}))$/.exec(time);
    if (!m) throw Error("Invalid time");
    const [year,month,day,hour,minute,second] = m.slice(1,7).map(Number), oh=Number(m[9]||0), om=Number(m[10]||0);
    if (year<1970 || year>2100 || month<1 || month>12 || day<1 || day>new Date(Date.UTC(year,month,0)).getUTCDate() || hour>23 || minute>59 || second>59 || oh>18 || om>59 || (oh===18 && om!==0)) throw Error("Invalid date");
    const meal = stringField(item,"meal",16);
    if (meal!==undefined && !["breakfast","lunch","dinner","snack","other"].includes(meal)) throw Error("Invalid meal");
  }
  return root;
}

function parseRequestLink(url) {
  if (typeof url !== "string" || url.length > 12000) throw Error("Invalid link");
  const match = /^https:\/\/food\.kukakur\.ru\/?#([A-Za-z0-9_-]+)$/.exec(url);
  if (!match || match[0] !== url || match[1].length > 10923) throw Error("Invalid link");
  const fragment = match[1];
  const bytes = Uint8Array.from(atob(fragment.replace(/-/g,"+").replace(/_/g,"/")), c=>c.charCodeAt(0));
  if (bytes.length > 8192) throw Error("Payload too large");
  const json = new TextDecoder("utf-8",{fatal:true,ignoreBOM:true}).decode(bytes);
  if (btoa(String.fromCharCode(...bytes)).replace(/\+/g,"-").replace(/\//g,"_").replace(/=+$/,"") !== fragment) throw Error("Noncanonical Base64URL");
  return validateRequest(json);
}
