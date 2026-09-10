"use strict";
// No network, storage or payload navigation: all user content is rendered as text.
function renderFood() {
  const preview = document.getElementById("preview"), error = document.getElementById("error");
  preview.replaceChildren(); preview.hidden=true; error.hidden=true;
  const fragment = location.hash.slice(1);
  if (!fragment) return;
  try {
    const request = parseRequestLink(location.href);
    const add = (parent,tag,text,className) => { const node=document.createElement(tag);node.textContent=text;if(className)node.className=className;parent.append(node);return node; };
    add(preview,"p","ДЕЙСТВИЯ ИЗ ССЫЛКИ","eyebrow");
    add(preview,"h2",request.items.length===1 ? "Одна операция" : `Операций: ${request.items.length}`);
    const deletes = request.items.filter(item=>item.op==="delete").length;
    if (deletes) add(preview,"p",`Удалений: ${deletes}. Весь список потребует ручного подтверждения в приложении.`,"warning");
    const meals = {breakfast:"Завтрак",lunch:"Обед",dinner:"Ужин",snack:"Перекус",other:"Приём пищи"};
    for (const [index,item] of request.items.entries()) {
      const section=add(preview,"article","","operation");
      if (item.op==="delete") {
        add(section,"h3",`${index+1}. Удалить блюдо`);
        add(section,"p",`ID: ${item.id}`,"small");
        add(section,"p",`Ревизия ${item.rev}. Удаление своей записи FoodBridge в Health Connect. Название будет проверено в приложении.`);
        continue;
      }
      add(section,"p",`${index+1}. Сохранить блюдо`,"eyebrow");
      add(section,"h3",item.name);add(section,"p",`${item.kcal} kcal`,"energy");
      add(section,"p",`ID: ${item.id} · Ревизия ${item.rev}`,"small");
      add(section,"p","Если блюдо уже существует, более новая ревизия заменит его состав.","small");
      if (item.kcal>4000 || [item.p,item.f,item.c,item.fiber,item.sugar,item.saturatedFat].some(v=>v>500) || item.sodiumMg>10000) add(section,"p","Необычно большие значения: потребуется ручное подтверждение.","warning");
      if (Date.parse(item.time)>Date.now()) add(section,"p","Время блюда ещё не наступило. Исправьте дату перед сохранением.","warning");
      const list=add(section,"dl","","nutrients");
      for (const [key,label,unit] of [["p","Белки","g"],["f","Жиры","g"],["c","Углеводы","g"],["fiber","Клетчатка","g"],["sugar","Сахар","g"],["saturatedFat","Насыщенные жиры","g"],["sodiumMg","Натрий","mg"]]) if (Object.hasOwn(item,key)) { add(list,"dt",label);add(list,"dd",`${item[key]} ${unit}`); }
      add(section,"p",`${meals[item.meal||"other"]} · ${item.time.replace("T"," ")}`);
    }
    add(preview,"p","Это только предпросмотр. Для выполнения откройте исходную ссылку в приложении FoodBridge.","small");
    if (request.items.length>1) add(preview,"p","Операции выполняются по очереди. При ошибке уже выполненные действия не отменяются.","small");
    preview.hidden=false;
  } catch { preview.replaceChildren();error.hidden=false; }
}
renderFood();
addEventListener("hashchange",renderFood);
