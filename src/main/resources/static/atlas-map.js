/* Self-hosted CC0 IBGE-derived map. No third-party map API or runtime library. */
const W=920,H=700,NS="http://www.w3.org/2000/svg";
const COLORS={13:"#df4557",22:"#368dd7",70:"#178a80",14:"#43a5b5",55:"#5f9d54",30:"#e78a36",80:"#9d2935",16:"#b53948",27:"#a5b468",21:"#b12d3b",35:"#618a95",29:"#9b3944",none:"#294450",other:"#8ba4b5"};
const clamp=(n,min,max)=>Math.max(min,Math.min(max,n));
export const normalizePlace=name=>String(name||"").normalize("NFD").replace(/[\u0300-\u036f]/g,"").toUpperCase().replace(/[^A-Z0-9]+/g," ").trim().replace(/\s+/g," ");
const stateCode=f=>String(f.properties?.sigla||"").toUpperCase();
const cityName=f=>String(f.properties?.nome||"");
const cityIbge=f=>String(f.properties?.cd_geocmu||"");
const mercator=p=>{
  const lat=clamp(Number(p[1]),-85,85)*Math.PI/180;
  return [Number(p[0]),-Math.log(Math.tan(Math.PI/4+lat/2))*180/Math.PI];
};
function rings(f){
  const geo=f?.geometry;
  if(!geo)return [];
  const polys=geo.type==="Polygon"?[geo.coordinates]:geo.type==="MultiPolygon"?geo.coordinates:[];
  return polys.flatMap(poly=>poly);
}
function bounding(features,project){
  let a=Infinity,b=Infinity,c=-Infinity,d=-Infinity;
  for(const f of features)for(const ring of rings(f))for(const p of ring){
    const [x,y]=project(p);
    if(!Number.isFinite(x)||!Number.isFinite(y))continue;
    a=Math.min(a,x);b=Math.min(b,y);c=Math.max(c,x);d=Math.max(d,y);
  }
  return Number.isFinite(a)?[a,b,c,d]:null;
}
function svgNode(tag,attrs={}){
  const element=document.createElementNS(NS,tag);
  for(const [key,value] of Object.entries(attrs))element.setAttribute(key,String(value));
  return element;
}
function geometryPath(feature,project){
  return rings(feature).filter(r=>r.length>=3).map(r=>
    r.map((point,i)=>{
      const [x,y]=project(point);
      return (i?"L":"M")+x.toFixed(1)+","+y.toFixed(1);
    }).join("")+"Z").join("");
}
function valid(x){return x?.type==="FeatureCollection"&&Array.isArray(x.features)&&x.features.length>0;}
export function createAtlasMap({onState,onCity,onReset,getCities,getStates,tooltip}){
  const target=document.getElementById("map-canvas"),cache=new Map();
  let country=null,uf="",selectedCode=null,selectedFeature=null;
  let projection=null,svg=null,land=null,camera={k:1,x:0,y:0};
  let suppressClickUntil=0,dragged=false,gesture=null;
  let focusSequence=0;
  const media=window.matchMedia("(prefers-reduced-motion: reduce)");
  function animateCamera(next){
    if(media.matches||!svg){camera=next;cameraApply();return;}
    const from={...camera},start=performance.now(),duration=340;
    function frame(now){
      if(!svg||focusSequence!==sequence)return;
      const t=Math.min(1,(now-start)/duration),e=1-Math.pow(1-t,3);
      camera={k:from.k+(next.k-from.k)*e,x:from.x+(next.x-from.x)*e,y:from.y+(next.y-from.y)*e};cameraApply();
      if(t<1)requestAnimationFrame(frame);
    }
    const sequence=focusSequence;requestAnimationFrame(frame);
  }
  const pointers=new Map();
  function project(p){
    const [x,y]=mercator(p);
    return [(x-projection.a)*projection.u+projection.dx,(y-projection.b)*projection.u+projection.dy];
  }
  function configureProjection(data){
    const b=bounding(data.features,mercator);
    if(!b)throw new Error("Malha nacional sem coordenadas");
    const u=Math.min((W-25)/(b[2]-b[0]),(H-25)/(b[3]-b[1]));
    projection={a:b[0],b:b[1],u,dx:(W-(b[2]-b[0])*u)/2,dy:(H-(b[3]-b[1])*u)/2};
  }
  function cameraApply(){
    if(land)land.setAttribute("transform","translate("+camera.x.toFixed(2)+" "+camera.y.toFixed(2)+") scale("+camera.k.toFixed(4)+")");
    const label=document.getElementById("map-zoom-value");
    if(label)label.textContent=camera.k<1.1?"BRASIL":camera.k.toFixed(1)+"×";
  }
  function fit(features,padding=35,maxZoom=60){
    const b=bounding(features,project);if(!b)return;
    const k=clamp(Math.min((W-2*padding)/Math.max(1,b[2]-b[0]),(H-2*padding)/Math.max(1,b[3]-b[1])),1,maxZoom);
    animateCamera({k,x:W/2-k*(b[0]+b[2])/2,y:H/2-k*(b[1]+b[3])/2});
  }
  function zoom(factor,x=W/2,y=H/2){
    const k=clamp(camera.k*factor,1,65),ratio=k/camera.k;
    camera.x=x-(x-camera.x)*ratio;camera.y=y-(y-camera.y)*ratio;camera.k=k;
    cameraApply();
  }
  function cursor(e){
    const r=svg.getBoundingClientRect();
    return {x:(e.clientX-r.left)*W/r.width,y:(e.clientY-r.top)*H/r.height};
  }
  function bindControls(){
    svg.addEventListener("wheel",e=>{
      e.preventDefault();const p=cursor(e);
      zoom(Math.exp(-clamp(e.deltaY,-220,220)*.0018),p.x,p.y);
    },{passive:false});
    svg.addEventListener("pointerdown",e=>{
      if(e.pointerType==="mouse"&&e.button!==0)return;
      const p=cursor(e);
      pointers.set(e.pointerId,{...p,startX:p.x,startY:p.y});
      dragged=false;gesture=null;
      // Do not capture an ordinary tap: SVG pointer capture retargets click to the
      // SVG root instead of the clicked state/municipal path.
    });
    svg.addEventListener("pointermove",e=>{
      if(!pointers.has(e.pointerId))return;
      const before=pointers.get(e.pointerId),after=cursor(e);
      pointers.set(e.pointerId,{...before,...after});
      if(pointers.size===1){
        const dx=after.x-before.x,dy=after.y-before.y;
        const moved=Math.hypot(after.x-before.startX,after.y-before.startY)>5;
        if(moved&&!dragged){
          dragged=true;
          try{svg.setPointerCapture(e.pointerId);}catch(ignore){}
        }
        if(dragged){camera.x+=dx;camera.y+=dy;cameraApply();}
      }else if(pointers.size===2){
        dragged=true;
        try{svg.setPointerCapture(e.pointerId);}catch(ignore){}
        const [a,b]=[...pointers.values()];
        const dist=Math.hypot(a.x-b.x,a.y-b.y),mid={x:(a.x+b.x)/2,y:(a.y+b.y)/2};
        if(gesture&&gesture.dist>1){
          camera.x+=mid.x-gesture.mid.x;camera.y+=mid.y-gesture.mid.y;
          zoom(dist/gesture.dist,mid.x,mid.y);
        }
        gesture={dist,mid};
      }
    });
    const done=e=>{
      pointers.delete(e.pointerId);
      if(dragged)suppressClickUntil=Date.now()+280;
      if(pointers.size<2)gesture=null;
    };
    svg.addEventListener("pointerup",done);
    svg.addEventListener("pointercancel",done);
    svg.addEventListener("dblclick",e=>{e.preventDefault();const p=cursor(e);zoom(1.8,p.x,p.y);});
  }
  function cityLinks(){
    const lookup=new Map();
    for(const city of getCities()||[]){
      const key=normalizePlace(city.name);
      if(lookup.has(key))lookup.set(key,null);
      else lookup.set(key,city);
    }
    return lookup;
  }
  function showFeature(f,isCity,region){
    tooltip.hidden=false;tooltip.replaceChildren();
    const strong=document.createElement("strong"),small=document.createElement("small");
    strong.textContent=isCity?cityName(f):stateCode(f);
    const top=region?.candidates?.[0];
    small.textContent=top
      ?(top.name+" · "+Number(top.percentage).toFixed(2).replace(".",",")+"% · "+Number(region.validVotes).toLocaleString("pt-BR")+" votos")
      :(isCity?"Geometria disponível; município não vinculado ao TSE":"Dados eleitorais não disponíveis");
    tooltip.append(strong,small);
  }
  function draw(){
    if(!country)return;
    const isCity=Boolean(uf),data=isCity?cache.get(uf):country;
    if(!data)return;
    const link=isCity?cityLinks():null;
    svg=svgNode("svg",{viewBox:"0 0 "+W+" "+H,role:"img",
      "aria-label":isCity?"Mapa interativo de municípios de "+uf:"Mapa completo dos estados brasileiros"});
    land=svgNode("g",{"class":"map-land"});
    for(const f of data.features){
      const id=isCity?cityIbge(f):stateCode(f);
      const region=isCity?link.get(normalizePlace(cityName(f))):(getStates()||[]).find(s=>s.code===id);
      const top=region?.candidates?.[0];
      const active=isCity?selectedFeature&&cityIbge(selectedFeature)===id:id===uf;
      const shape=svgNode("path",{d:geometryPath(f,project),
        fill:top?(COLORS[top.number]||COLORS.other):COLORS.none,
        "class":"map-path "+(isCity?"map-municipality":"map-state")+(active?" is-selected":""),
        "data-place":id,
        tabindex:isCity?"-1":"0",
        "aria-label":isCity?cityName(f):"Estado "+id});
      shape.addEventListener("mouseenter",()=>showFeature(f,isCity,region));
      shape.addEventListener("mouseleave",()=>{tooltip.hidden=true;});
      shape.addEventListener("focus",()=>showFeature(f,isCity,region));
      shape.addEventListener("blur",()=>{tooltip.hidden=true;});
      const activate=()=>{
        if(Date.now()<suppressClickUntil)return;
        if(!isCity){onState(id);return;}
        if(!region){showFeature(f,true,null);return;}
        selectedCode=region.code;selectedFeature=f;
        onCity({uf,code:region.code,name:region.name,ibge:id});
        draw();fit([f],66,60);
      };
      shape.addEventListener("click",activate);
      shape.addEventListener("keydown",e=>{
        if(e.key==="Enter"||e.key===" "){e.preventDefault();activate();}
      });
      land.append(shape);
    }
    svg.append(land);target.replaceChildren(svg);bindControls();cameraApply();
    target.classList.add("map-ready");
  }
  async function boot(){
    const r=await fetch("/geo/estados.geojson",{cache:"force-cache"});
    if(!r.ok)throw new Error("Mapa nacional HTTP "+r.status);
    const d=await r.json();
    if(!valid(d)||d.features.length!==27)throw new Error("Mapa nacional incompleto");
    country=d;configureProjection(d);draw();fit(country.features,12,2);
  }
  async function focusState(next){
    const sequence=++focusSequence;
    uf=next||"";selectedCode=null;selectedFeature=null;tooltip.hidden=true;
    if(!country)return;
    // Zoom to the selected UF immediately, even while municipality geometry loads.
    if(uf){const outline=country.features.find(f=>stateCode(f)===uf);if(outline)fit([outline],28,45);}
    if(uf&&!cache.has(uf)){
      target.setAttribute("aria-busy","true");
      try{
        const r=await fetch("/geo/"+uf+".geojson",{cache:"force-cache"});
        if(!r.ok)throw new Error("HTTP "+r.status);
        const data=await r.json();
        if(sequence!==focusSequence)return;
        if(!valid(data))throw new Error("Dados cartográficos incompletos");
        cache.set(uf,data);
      }catch(e){
        // Preserve the state focus even if its detailed municipal mesh is absent.
        draw();const outline=country.features.find(f=>stateCode(f)===uf);
        if(outline)fit([outline],28,45);
        throw e;
      }finally{target.removeAttribute("aria-busy");}
    }
    if(sequence!==focusSequence)return;
    draw();
    if(!uf)fit(country.features,12,2);
    else {
      const bound=country.features.find(f=>stateCode(f)===uf);
      if(bound)fit([bound],36,45);
    }
  }
  function focusCityByCode(code,name){
    if(!uf||!cache.has(uf))return false;
    const f=cache.get(uf).features.find(x=>normalizePlace(cityName(x))===normalizePlace(name));
    if(!f)return false;
    selectedCode=code;selectedFeature=f;draw();fit([f],60,65);return true;
  }
  function refresh(){draw();}
  function reset(){onReset();}
  return {boot,focusState,focusCityByCode,refresh,zoom,reset,
    get state(){return {uf,selectedCode,ready:Boolean(country),camera:{...camera}};}};
}
