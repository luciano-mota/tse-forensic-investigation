import test from "node:test";
import assert from "node:assert/strict";
import {readFileSync} from "node:fs";
const src=readFileSync(new URL("../src/main/resources/static/atlas-map.js",import.meta.url),"utf8");
const {createAtlasMap,normalizePlace}=await import("data:text/javascript;base64,"+Buffer.from(src).toString("base64"));

class Element {
  constructor(tag){this.tagName=tag;this.attributes={};this.children=[];this.listeners={};this.hidden=true;this.textContent="";this.captured=0;}
  setAttribute(k,v){this.attributes[k]=String(v);}
  getAttribute(k){return this.attributes[k];}
  append(...nodes){this.children.push(...nodes);}
  replaceChildren(...nodes){this.children=[...nodes];}
  addEventListener(k,fn){(this.listeners[k]??=[]).push(fn);}
  fire(k,data={}){for(const fn of this.listeners[k]||[])fn({type:k,preventDefault(){},...data});}
  setPointerCapture(){this.captured++;}
  getBoundingClientRect(){return {left:0,top:0,width:920,height:610};}
}
const coords=[[-45,-6],[-44,-6],[-44,-5],[-45,-5],[-45,-6]];
const poly=(uf,i)=>({type:"Feature",properties:{sigla:uf},geometry:{type:"Polygon",
  coordinates:[coords.map(([x,y])=>[x+(i%8)*2.7,y+Math.floor(i/8)*2.7])]}});
function mock(){
 const states=["AC","AL","AM","AP","BA","CE","DF","ES","GO","MA","MG","MS","MT",
 "PA","PB","PE","PI","PR","RJ","RN","RO","RR","RS","SC","SE","SP","TO"];
 const country={type:"FeatureCollection",features:states.map((uf,i)=>poly(uf,i))};
 const cities={type:"FeatureCollection",features:[
   {type:"Feature",properties:{nome:"São Luís",cd_geocmu:"2111300"},
     geometry:{type:"Polygon",coordinates:[coords]}},
   {type:"Feature",properties:{nome:"Itapecuru Mirim",cd_geocmu:"2105401"},
     geometry:{type:"Polygon",coordinates:[coords.map(([x,y])=>[x+.2,y+.2])]}}
 ]};
 let current=null;
 const canvas=new Element("div"), zoom=new Element("output"), tooltip=new Element("div");
 globalThis.document={
  createElementNS:tag=>new Element(tag),
  createElement:tag=>new Element(tag),
  getElementById:id=>({"map-canvas":canvas,"map-zoom-value":zoom})[id]??null
 };
 globalThis.fetch=async url=>({ok:true,json:async()=>url.includes("estados")?country:cities});
 const log=[];
 const map=createAtlasMap({
    onState:uf=>log.push({type:"state",uf}),
    onCity:city=>log.push({type:"city",city}),
    onReset:()=>log.push({type:"reset"}),
    getCities:()=>[
      {code:"777",name:"São Luís",candidates:[{number:13,name:"Lula",percentage:60}],validVotes:1234},
      {code:"778",name:"Itapecuru Mirim",candidates:[{number:22,name:"Outro",percentage:50}],validVotes:234}],
    getStates:()=>[{code:"MA",candidates:[{number:13,name:"Lula",percentage:60}],validVotes:1000}],
    tooltip
 });
 return {map,canvas,tooltip,log,zoom,country,cities};
}
function shapes(canvas,type){
 const found=[];
 const walk=node=>{
   if(String(node?.attributes?.class||"").split(/\\s+/).includes(type))found.push(node);
   for(const child of node?.children||[])walk(child);
 };
 walk(canvas);
 return found;
}
test("geographic normalization matches accented TSE municipality names",()=>{
 assert.equal(normalizePlace("São Luís - MA"),"SAO LUIS MA");
 assert.equal(normalizePlace("Itapecuru-Mirim"),"ITAPECURU MIRIM");
});
test("tap selects UF without a pointer capture, zooms to municipality boundaries",async()=>{
 const {map,canvas,log}=mock();
 await map.boot();
 console.log("MOCK DOM BOOT",JSON.stringify({root:canvas.children.map(x=>({tag:x.tagName,
  children:x.children.map(y=>({tag:y.tagName,count:y.children?.length,classes:y.attributes?.class,
  first:y.children?.[0]?.attributes?.class}))}))}));
 assert.equal(shapes(canvas,"map-state").length,27);
 const svg=canvas.children[0],ma=shapes(canvas,"map-state").find(x=>x.attributes["data-place"]==="MA");
 assert.ok(ma);
 svg.fire("pointerdown",{pointerId:1,clientX:100,clientY:110,pointerType:"mouse",button:0});
 assert.equal(svg.captured,0,"simple tap must not capture click target");
 svg.fire("pointerup",{pointerId:1});
 ma.fire("click");
 assert.deepEqual(log[0],{type:"state",uf:"MA"});
 await map.focusState("MA");
 assert.equal(shapes(canvas,"map-municipality").length,2);
 assert.equal(map.state.uf,"MA");
 assert.ok(map.state.camera.k>1);
 const city=shapes(canvas,"map-municipality").find(x=>x.attributes["aria-label"]==="São Luís");
 assert.ok(city,"City polygon should exist and be clickable");
 const previous=map.state.camera.k;
 city.fire("click");
 assert.deepEqual(log[1],{type:"city",city:{uf:"MA",code:"777",name:"São Luís",ibge:"2111300"}});
 assert.equal(map.state.selectedCode,"777");
 assert.ok(map.state.camera.k>=previous);
 await map.focusState("");
 assert.equal(shapes(canvas,"map-state").length,27);
 assert.equal(map.state.uf,"");
});
test("drag captures only after move threshold and prevents accidental clicks",async()=>{
 const {map,canvas,log}=mock();await map.boot();
 const svg=canvas.children[0],ma=shapes(canvas,"map-state").find(x=>x.attributes["data-place"]==="MA");
 svg.fire("pointerdown",{pointerId:2,clientX:100,clientY:100,pointerType:"mouse",button:0});
 svg.fire("pointermove",{pointerId:2,clientX:101,clientY:102});
 assert.equal(svg.captured,0);
 svg.fire("pointermove",{pointerId:2,clientX:120,clientY:121});
 assert.equal(svg.captured,1);
 svg.fire("pointerup",{pointerId:2});
 ma.fire("click");
 assert.equal(log.length,0);
});
