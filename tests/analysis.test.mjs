import assert from "node:assert/strict";
import test from "node:test";
import {readFileSync} from "node:fs";
// Explicit data URL enables ESM tests even without a package.json.
const data=readFileSync(new URL("../src/main/resources/static/analysis.js",import.meta.url),"utf8");
const {buildElectionReading}=await import("data:text/javascript;base64,"+Buffer.from(data).toString("base64"));
const model={
    rounds:{
      "1":{MA:{municipalities:{"100":{name:"CIDADE A",candidates:{"13":30,"22":50,"70":10},
                    turnout:{registered:150,turnout:110,abstentions:40}}}}},
      "2":{MA:{municipalities:{"100":{name:"CIDADE A",candidates:{"13":68,"22":52},
                    turnout:{registered:150,turnout:130,abstentions:20}}}}}
    },
    sources:{votes:{url:"https://cdn.tse.jus.br/votos.zip",sha256:"a".repeat(64)},
             turnout:{url:"https://cdn.tse.jus.br/comparecimento.zip",sha256:"b".repeat(64)}},
    observed_at:"2026-11-01T05:00:00Z",derived_version:"c".repeat(64),
    national_reference:{status:"REVIEW_DIFFERENCE"}
};
test("turnout and absentee change are calculated using registered totals",()=>{
 const reading=buildElectionReading(model,{uf:"MA",municipalityCode:"100",name:"CIDADE A"});
 assert.equal(reading.status,"DESCRIPTIVE");
 assert.equal(reading.scope.turnout1,110);
 assert.equal(reading.scope.turnout2,130);
 assert.equal(reading.scope.deltaTurnout,20);
 assert.equal(reading.scope.deltaAbstentions,-20);
 assert.ok(Math.abs(reading.scope.deltaAbstentionPp+13.33333)<0.0001);
 assert.equal(reading.calculations.length,5);
 assert.ok(reading.calculations[3].result.includes("-13,33 p.p."));
 assert.ok(reading.sources.every(s=>s.sha256.length===64));
 assert.match(reading.caveat,/Não é possível inferir transferência individual/);
 assert.doesNotMatch(reading.lead,/fraude comprovada/i);
});
test("national 39 duplicate apt registries are explicitly qualified",()=>{
 const reading=buildElectionReading(model,{name:"Brasil"});
 assert.match(reading.referenceNote,/39 registros/);
 assert.match(reading.checks[1].detail,/revisão/);
});
test("insufficient round data never produces artificial conclusion",()=>{
 const reading=buildElectionReading({rounds:{"1":{},"2":{}}},{uf:"MA"});
 assert.equal(reading.status,"INSUFFICIENT");
 assert.equal(reading.calculations.length,0);
});
test("mismatch is reported as uncertain and is not labeled as fraud",()=>{
 const bad=structuredClone(model);
 bad.rounds["2"].MA.municipalities["100"].turnout.abstentions=10;
 const reading=buildElectionReading(bad,{uf:"MA"});
 assert.equal(reading.checks[0].kind,"warning");
 assert.match(reading.checks[0].detail,/reconciliação documental/);
});
