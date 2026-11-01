/**
 * Deterministic descriptive interpretation of the official 2026 TSE aggregates.
 * No generative conclusions, inference of voter transfers, personal data or fraud scores.
 * Every number comes from the published snapshot and has an explicit formula.
 */
const pct=(n,d)=>d>0?100*n/d:null;
const diff=(a,b)=>a!=null&&b!=null?b-a:null;
const fmt=n=>new Intl.NumberFormat("pt-BR",{maximumFractionDigits:2}).format(n);
const pp=n=>n===null?"indisponível":(n>0?"+":"")+fmt(n)+" p.p.";
const signed=n=>(n>0?"+":"")+fmt(n);
const scopeData=(atlas,round,uf,municipalityCode)=>{
  const states=atlas?.rounds?.[String(round)];
  if(!states)return null;
  const groups=uf?(states[uf]?[states[uf]]:[]):Object.values(states);
  if(!groups.length)return null;
  const results={round,registered:0,turnout:0,abstentions:0,candidates:{},municipalities:0,covered:0};
  for(const state of groups){
    const cities=municipalityCode?
      (state.municipalities?.[municipalityCode]?[state.municipalities[municipalityCode]]:[]):
      Object.values(state.municipalities||{});
    for(const city of cities){
      results.municipalities++;
      if(city.turnout){
        results.covered++;
        results.registered+=Number(city.turnout.registered)||0;
        results.turnout+=Number(city.turnout.turnout)||0;
        results.abstentions+=Number(city.turnout.abstentions)||0;
      }
      for(const [number,count] of Object.entries(city.candidates||{}))
        results.candidates[number]=(results.candidates[number]||0)+Number(count);
    }
  }
  results.validVotes=Object.values(results.candidates).reduce((a,b)=>a+b,0);
  results.abstentionRate=pct(results.abstentions,results.registered);
  results.turnoutRate=pct(results.turnout,results.registered);
  results.integrityDifference=results.registered-results.turnout-results.abstentions;
  return results;
};
export function buildElectionReading(atlas,{uf="",municipalityCode=null,name="Brasil"}={}){
  if(!atlas?.rounds?.["1"]||!atlas?.rounds?.["2"])return null;
  const first=scopeData(atlas,1,uf,municipalityCode);
  const second=scopeData(atlas,2,uf,municipalityCode);
  if(!first||!second||!first.registered||!second.registered||
     !first.covered||!second.covered||!first.validVotes||!second.validVotes){
    return {status:"INSUFFICIENT",name,headline:"Cobertura insuficiente para este recorte",
      explanation:"Os dois turnos precisam conter votos válidos e dados de comparecimento para uma leitura comparável.",
      calculations:[],checks:[],formulas:[],interpretations:[]};
  }
  const deltaRate=diff(first.abstentionRate,second.abstentionRate);
  const deltaVoters=second.turnout-first.turnout;
  const deltaAbstentions=second.abstentions-first.abstentions;
  const gap13=(second.candidates["13"]||0)-(second.candidates["22"]||0);
  const leadNum=gap13===0?"empatados":gap13>0?"13":"22";
  const leadCount=Math.abs(gap13);
  const leadPct=pct(leadCount,second.validVotes);
  const vote13First=first.candidates["13"]||0, vote13Second=second.candidates["13"]||0;
  const vote22First=first.candidates["22"]||0, vote22Second=second.candidates["22"]||0;
  const share13First=pct(vote13First,first.validVotes),share13Second=pct(vote13Second,second.validVotes);
  const share22First=pct(vote22First,first.validVotes),share22Second=pct(vote22Second,second.validVotes);
  const knownMismatch=first.integrityDifference!==0||second.integrityDifference!==0;
  const reference=atlas.national_reference?.status;
  const national=!uf&&!municipalityCode;
  const time=atlas.observed_at||null;
  const label=name||"o recorte selecionado";
  let headline;
  if(deltaRate<0)headline="Abstenção menor no segundo turno em "+label;
  else if(deltaRate>0)headline="Abstenção maior no segundo turno em "+label;
  else headline="Taxa de abstenção estável entre turnos em "+label;
  const interpretations=[
    "Comparecimento: "+fmt(first.turnout)+" eleitores no 1º turno e "+fmt(second.turnout)+
    " no 2º. Diferença observada: "+signed(deltaVoters)+".",
    "Abstenção: "+fmt(first.abstentions)+" no 1º turno ("+fmt(first.abstentionRate)+
    "% dos registros aptos) e "+fmt(second.abstentions)+" no 2º ("+
    fmt(second.abstentionRate)+"%). Mudança: "+pp(deltaRate)+".",
    "Votos válidos: "+fmt(first.validVotes)+" no 1º turno e "+
       fmt(second.validVotes)+" no 2º. Esses totais não incluem votos brancos e nulos.",
    "Candidato 13: "+fmt(vote13First)+" → "+fmt(vote13Second)+
    " votos nominais, variação de participação entre válidos de "+pp(share13Second-share13First)+
    ". Candidato 22: "+fmt(vote22First)+" → "+fmt(vote22Second)+
    ", variação de "+pp(share22Second-share22First)+"."
  ];
  if(leadNum!=="empatados")interpretations.push(
    "No 2º turno, o número "+leadNum+" obteve "+fmt(leadCount)+
    " votos válidos a mais que o número "+(leadNum==="13"?"22":"13")+
    ", diferença equivalente a "+fmt(leadPct)+"% dos votos válidos do recorte.");
  else interpretations.push("Os dois candidatos terminaram empatados nos votos válidos deste recorte.");
  const calculations=[
    {label:"Variação de comparecimento",formula:"Comparecimento 2º − Comparecimento 1º",
     expression:fmt(second.turnout)+" − "+fmt(first.turnout),result:signed(deltaVoters)+" pessoas"},
    {label:"Taxa de abstenção no 1º turno",formula:"100 × Abstenções 1º ÷ Aptos 1º",
     expression:"100 × "+fmt(first.abstentions)+" ÷ "+fmt(first.registered),
     result:fmt(first.abstentionRate)+"%"},
    {label:"Taxa de abstenção no 2º turno",formula:"100 × Abstenções 2º ÷ Aptos 2º",
     expression:"100 × "+fmt(second.abstentions)+" ÷ "+fmt(second.registered),
     result:fmt(second.abstentionRate)+"%"},
    {label:"Diferença da abstenção",formula:"Taxa 2º − Taxa 1º",
     expression:fmt(second.abstentionRate)+"% − "+fmt(first.abstentionRate)+"%",
     result:pp(deltaRate)},
    {label:"Margem de votos no 2º turno",formula:"|Votos 13 − Votos 22| ÷ Votos válidos × 100",
     expression:"|"+fmt(vote13Second)+" − "+fmt(vote22Second)+"| ÷ "+fmt(second.validVotes)+" × 100",
     result:fmt(leadPct)+"% dos votos válidos"}
  ];
  const checks=[
    {kind:knownMismatch?"warning":"pass",label:"Balanço de comparecimento",
      detail:knownMismatch?
      "Diferença entre aptos e (comparecimento + abstenções): "+
      fmt(first.integrityDifference)+" no 1º turno; "+fmt(second.integrityDifference)+
      " no 2º. Pode envolver regras de totalização e precisa de reconciliação documental.":
      "Os valores de aptos, comparecimento e abstenções fecham aritmeticamente neste recorte."},
    {kind: national&&reference!=="MATCHES_TSE_PUBLISHED_TOTALS"?"warning":"info",
     label:"Conferência com totais de referência",
     detail:national?(reference==="MATCHES_TSE_PUBLISHED_TOTALS"?
       "Votos nacionais do 2º turno coincidem com a referência do TSE configurada no projeto.":
       "A comparação com a referência nacional requer revisão de cobertura e transformações."):
       "Esta conferência nacional não constitui uma validação independente de cada município."}
  ];
  const sources=Object.entries(atlas.sources||{}).map(([kind,s])=>({
    type:kind,filename:(s.url||"").split("/").pop(),url:s.url,sha256:s.sha256,
    downloadedAt:s.downloaded_at,resourceId:s.id
  }));
  return {status:"DESCRIPTIVE",name:label,headline,
    lead:"Os dados mostram "+(deltaRate<0?"redução":deltaRate>0?"aumento":"estabilidade")+
      " de "+fmt(Math.abs(deltaRate))+" ponto(s) percentual(is) na taxa de abstenção entre turnos. "+
      "O conjunto permite descrever essa diferença, não determinar por que cada pessoa compareceu ou faltou.",
    interpretations,calculations,checks,sources,
    scope:{uf,municipalityCode,municipalities:first.municipalities,
      turnout1:first.turnout,turnout2:second.turnout,absent1:first.abstentions,absent2:second.abstentions,
      apt1:first.registered,apt2:second.registered,
      deltaTurnout:deltaVoters,deltaAbstentions,deltaAbstentionPp:deltaRate},
    observedAt:time,version:atlas.derived_version,
    caveat:"Não é possível inferir transferência individual de votos, causa do comparecimento, fraude, " +
      "erro de identificação, uso de biometria ou integridade do software das urnas a partir desses totais.",
    methodology:"Método determinístico: soma por turno das linhas presidenciais municipais de uma mesma UF e município; " +
      "calcula razão percentual com denominador aptos ou votos válidos conforme a métrica; compara taxas em pontos percentuais. " +
      "Os dados vêm dos ZIPs públicos do TSE, processados temporariamente; apenas derivados e hashes são preservados.",
    referenceNote:national?
      "O TSE alerta que 39 registros de transferência temporária constam duplamente dos arquivos de aptos; " +
      "taxas apresentadas usam o denominador bruto do arquivo até ajuste oficial documentado.":null};
}
