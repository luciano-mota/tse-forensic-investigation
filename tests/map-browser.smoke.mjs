import { chromium } from "playwright";
import fs from "node:fs/promises";
import assert from "node:assert/strict";

const base=process.env.MAP_TEST_URL||"http://127.0.0.1:8765";
const out="map-test-artifacts";
await fs.mkdir(out,{recursive:true});
const browser=await chromium.launch({headless:true,args:["--no-sandbox"]});
const errors=[];
const sample={
 schema:1,status:"DERIVED_FROM_OFFICIAL_FILES_NOT_ORIGINAL_ARCHIVE",
 derived_version:"a".repeat(64),observed_at:"2026-11-01T05:00:00Z",
 national_reference:{status:"MATCHES_TSE_PUBLISHED_TOTALS"},
 sources:{votes:{url:"https://cdn.tse.jus.br/test-votes.zip",sha256:"b".repeat(64)},
          turnout:{url:"https://cdn.tse.jus.br/test-turnout.zip",sha256:"c".repeat(64)}},
 parse_stats:{turnout:{arithmetic_mismatches:0}},
 rounds:{
 "1":{
  MA:{candidates:{"13":120,"22":100},
    municipalities:{"12345":{name:"São Luís",candidates:{"13":70,"22":50},turnout:{registered:200,turnout:160,abstentions:40}},
                   "77777":{name:"Itapecuru Mirim",candidates:{"13":50,"22":50},turnout:{registered:200,turnout:170,abstentions:30}}}},
  SP:{candidates:{"13":90,"22":110},
    municipalities:{"98765":{name:"São Paulo",candidates:{"13":90,"22":110},turnout:{registered:300,turnout:240,abstentions:60}}}}
 },
 "2":{
  MA:{candidates:{"13":130,"22":120},
    municipalities:{"12345":{name:"São Luís",candidates:{"13":75,"22":60},turnout:{registered:200,turnout:180,abstentions:20}},
                   "77777":{name:"Itapecuru Mirim",candidates:{"13":55,"22":60},turnout:{registered:200,turnout:180,abstentions:20}}}},
  SP:{candidates:{"13":120,"22":100},
    municipalities:{"98765":{name:"São Paulo",candidates:{"13":120,"22":100},turnout:{registered:300,turnout:260,abstentions:40}}}}
 }
}};
async function configure(page) {
 page.on("pageerror",e=>errors.push(e.stack||e.message));
 await page.route("**/api/v1/**",async route=>{
  const path=new URL(route.request().url()).pathname;
  let data;
  if(path==="/api/v1/live/atlas")data=sample;
  else if(path==="/api/v1/live/status")data={status:"OK",version:sample.derived_version,
     checkedAt:"2026-11-01T05:00:00Z",snapshotAt:sample.observed_at};
  else if(path==="/api/v1/elections/overview")data={validVotes:0,candidates:[]};
  else if(path==="/api/v1/elections/states")data=[];
  else if(path==="/api/v1/sources")data=[];
  else if(path==="/api/v1/archive/history")data={snapshots:[],poll_count:0};
  else if(path==="/api/v1/audits/national-reference")data={status:"INCOMPLETE",missingUfs:["AC"]};
  else if(path.includes("/api/v1/audits/")){await route.fulfill({status:404,body:"{}"});return;}
  else {await route.fulfill({status:404,body:"{}"});return;}
  await route.fulfill({status:200,contentType:"application/json",body:JSON.stringify(data)});
 });
 await page.route("https://commons.wikimedia.org/**",route=>route.abort());
}
async function waitFor(page,selector,min=1){
 await page.waitForFunction(args=>document.querySelectorAll(args.selector).length>=args.min,{selector,min},{timeout:20000});
}
const ctx=await browser.newContext({viewport:{width:1500,height:1000},deviceScaleFactor:1});
const page=await ctx.newPage();
try {
 await configure(page);
 await page.goto(base,{waitUntil:"domcontentloaded",timeout:30000});
 await waitFor(page,"#map-canvas svg path.map-state",27);
 await page.waitForFunction(()=>document.querySelector("#region-name")?.textContent==="Brasil");
 const initial=await page.locator("#map-canvas g.map-land").getAttribute("transform");
 assert.match(initial,/scale\(/);
 const ma=page.locator("#map-canvas path.map-state[data-place='MA']");
 assert.equal(await ma.count(),1);
 await ma.click({force:true,timeout:20000});
 await waitFor(page,"#map-canvas path.map-municipality",217);
 assert.match(await page.locator("#map-trail").innerText(),/MA/);
 assert.match(await page.locator("#region-name").innerText(),/Maranhão/);
 const stateTransform=await page.locator("#map-canvas g.map-land").getAttribute("transform");
 assert.notEqual(stateTransform,initial);
 const stateZoom=parseFloat(stateTransform.match(/scale\(([^)]+)/)[1]);
 assert.ok(stateZoom>1,"Selecting a UF should zoom into it");
 await page.screenshot({path:out+"/ma-zoom.png",fullPage:false});
 await page.locator("#map-canvas path.map-municipality[aria-label='São Luís']").dispatchEvent("click");
 await page.waitForFunction(()=>document.querySelector("#region-name")?.textContent==="São Luís");
 assert.match(await page.locator("#map-trail").innerText(),/SÃO LUÍS/);
 assert.match(await page.locator("#report-pdf").getAttribute("href"),/municipalityCode=12345/);
 const cityTransform=await page.locator("#map-canvas g.map-land").getAttribute("transform");
 assert.notEqual(cityTransform,stateTransform);
 await page.screenshot({path:out+"/municipality-zoom.png",fullPage:false});
 await page.locator("#map-reset").click();
 await waitFor(page,"#map-canvas path.map-state",27);
 await page.locator("#city-search").fill("itapecuru mirim");
 await page.waitForFunction(()=>document.querySelectorAll("#city-rows tr[data-municipality]").length===1);
 assert.match(await page.locator("#city-rows").innerText(),/Itapecuru Mirim/);
 await page.locator("#city-rows tr[data-municipality]").click();
 await page.waitForFunction(()=>document.querySelector("#region-name")?.textContent==="Itapecuru Mirim");
 assert.match(await page.locator("#report-pdf").getAttribute("href"),/municipalityCode=77777/);
 await page.locator("#map-reset").click();
 await page.locator("#city-search").fill("sao luis");
 await page.waitForFunction(()=>document.querySelectorAll("#city-rows tr[data-municipality]").length===1);
 await page.locator("#city-rows tr[data-municipality]").press("Enter");
 await page.waitForFunction(()=>document.querySelector("#region-name")?.textContent==="São Luís");
 await page.screenshot({path:out+"/city-search.png",fullPage:false});
 await page.locator("#map-reset").click();
 await page.locator("#city-search").fill("São Paulo SP");
 await page.waitForFunction(()=>document.querySelectorAll("#city-rows tr[data-municipality]").length===1);
 await page.locator("#city-rows tr[data-municipality]").click();
 await page.waitForFunction(()=>document.querySelector("#region-name")?.textContent==="São Paulo");
 assert.match(await page.locator("#map-trail").innerText(),/SP/);
 await page.locator("#map-reset").click();
 await page.locator("#map-zoom-in").click();
 const manualZoom=await page.locator("#map-zoom-value").innerText();
 assert.match(manualZoom,/×/);
 await page.screenshot({path:out+"/desktop-atlas.png",fullPage:false});
 assert.equal(errors.length,0,"Unexpected JS browser errors: "+errors.join("\n"));
 console.log("PASS: physical state click MA, polygon zoom São Luís, keyboard & accent-insensitive national search, PDF scope, map controls.");
} finally {
 await page.close();
 const mobile=await browser.newContext({viewport:{width:390,height:844},isMobile:true,hasTouch:true});
 const m=await mobile.newPage();
 try{
   await configure(m);
   await m.goto(base,{waitUntil:"domcontentloaded",timeout:30000});
   await waitFor(m,"#map-canvas path.map-state",27);
   const h=await m.locator("#map-canvas").evaluate(el=>Math.round(el.getBoundingClientRect().height));
   assert.ok(h>=350,"Mobile map is too small: "+h);
   await m.screenshot({path:out+"/mobile-atlas.png",fullPage:false});
 } finally {await mobile.close();}
 await browser.close();
}
