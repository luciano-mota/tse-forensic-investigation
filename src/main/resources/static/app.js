import { createAtlasMap, normalizePlace } from "./atlas-map.js";
import { buildElectionReading } from "./analysis.js";
const API = "/api/v1";
const UF_NAMES = {
    AC: "Acre", AL: "Alagoas", AP: "Amapá", AM: "Amazonas", BA: "Bahia",
    CE: "Ceará", DF: "Distrito Federal", ES: "Espírito Santo", GO: "Goiás",
    MA: "Maranhão", MT: "Mato Grosso", MS: "Mato Grosso do Sul", MG: "Minas Gerais",
    PA: "Pará", PB: "Paraíba", PR: "Paraná", PE: "Pernambuco", PI: "Piauí",
    RJ: "Rio de Janeiro", RN: "Rio Grande do Norte", RS: "Rio Grande do Sul",
    RO: "Rondônia", RR: "Roraima", SC: "Santa Catarina", SP: "São Paulo",
    SE: "Sergipe", TO: "Tocantins", ZZ: "Exterior"
};
const IBGE_CODES = {
    11: "RO", 12: "AC", 13: "AM", 14: "RR", 15: "PA", 16: "AP", 17: "TO",
    21: "MA", 22: "PI", 23: "CE", 24: "RN", 25: "PB", 26: "PE", 27: "AL",
    28: "SE", 29: "BA", 31: "MG", 32: "ES", 33: "RJ", 35: "SP",
    41: "PR", 42: "SC", 43: "RS", 50: "MS", 51: "MT", 52: "GO", 53: "DF"
};
const LABELS = {
  13:"Lula (PT)",22:"Flávio Bolsonaro (PL)",70:"Augusto Cury (Avante)",
  14:"Renan Santos (Missão)",55:"Ronaldo Caiado (PSD)",30:"Romeu Zema (Novo)",
  80:"Samara Martins (UP)",16:"Hertz Dias (PSTU)",27:"Clariana Barão (DC)",
  21:"Edmilson Costa (PCB)",35:"Wilson Grassi (Democrata)",29:"Rui Costa Pimenta (PCO)"
};
const CANDIDATE_PRESENTATION=Object.freeze({
  13:{party:"PT",color:"#df4557",picture:"https://commons.wikimedia.org/wiki/Special:Redirect/file/09.07.2026%20-%20Presidente%20Lula%20-%2055383588588.jpg?width=240"},
  22:{party:"PL",color:"#368dd7",picture:"https://commons.wikimedia.org/wiki/Special:Redirect/file/2026%20FLAVIO%20BOLSONARO%20CANDIDATO%20PRESIDENTE%20TSE%20%28280002551544%29%20%28cropped%29.jpg?width=240"},
  70:{party:"Avante",color:"#178a80"},14:{party:"Missão",color:"#43a5b5"},
  55:{party:"PSD",color:"#5f9d54"},30:{party:"Novo",color:"#e78a36"},
  80:{party:"UP",color:"#9d2935"},16:{party:"PSTU",color:"#b53948"},
  27:{party:"DC",color:"#a5b468"},21:{party:"PCB",color:"#b12d3b"},
  35:{party:"Democrata",color:"#618a95"},29:{party:"PCO",color:"#9b3944"}
});
const numberFormat = new Intl.NumberFormat("pt-BR");
const percentageFormat = new Intl.NumberFormat("pt-BR", { maximumFractionDigits: 2, minimumFractionDigits: 2 });
const state = {
    round: 2,
    uf: "",
    municipalityCode: null,
    municipalityName: null,
    overview: null,
    states: [],
    municipalities: [],
    sources: [],
    mapFeatures: null,
    geoModule: null,
    mapController: null,
    error: null,
    hasData: false,
    archive: null,
    liveAtlas: null,
    hasDbData: false,
    lastSourceStatus: null,
    loadingOfficialAtlas: false
};

const $ = id => document.getElementById(id);
let nationalCities = null;
let nationalCitiesLoading = null;
// DB-backed national search loads each state once, with bounded parallelism.
async function ensureNationalCities(){
    if(!state.hasDbData || nationalCities || nationalCitiesLoading)return nationalCitiesLoading;
    nationalCitiesLoading=(async()=>{
        const ufs=Object.keys(UF_NAMES).filter(uf=>uf!=="ZZ");
        const items=[];let next=0,failures=0;
        await Promise.all(Array.from({length:4},async()=>{
            while(next<ufs.length){
                const uf=ufs[next++];
                try{const cities=await getJson("/elections/states/"+uf+"/municipalities?round="+state.round);
                    items.push(...cities.map(city=>({...city,uf})));
                }catch(error){failures++;}
            }
        }));
        nationalCities=items;
        if(failures){$("city-search-feedback").textContent="Busca parcial: "+failures+" UF(s) indisponíveis na API.";}
        renderMunicipalities();
    })().finally(()=>{nationalCitiesLoading=null;});
    return nationalCitiesLoading;
}
const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
}[char]));
const votes = value => numberFormat.format(value ?? 0);
const pct = value => percentageFormat.format(Number(value ?? 0)) + "%";
const winner = region => region?.candidates?.[0] ?? null;
const label = candidate => LABELS[candidate.number] ||
    (candidate.name?.startsWith("Votável ") ? "Votável " + candidate.number : candidate.name);
const selectedRegion = () => state.municipalityCode
    ? (state.municipalities.find(r => r.code === state.municipalityCode) || null)
    : state.uf ? state.states.find(r => r.code === state.uf) : state.overview;

async function getJson(path) {
    const response = await fetch(API + path, { headers: { Accept: "application/json" } });
    if (!response.ok) {
        throw new Error("API indisponível: " + response.status);
    }
    return response.json();
}

function setup() {
    const select = $("uf-select");
    Object.entries(UF_NAMES).forEach(([code, name]) => {
        const option = document.createElement("option");
        option.value = code;
        option.textContent = name + " · " + code;
        select.append(option);
    });
    document.querySelectorAll("[data-round]").forEach(button => {
        button.addEventListener("click", async () => {
            state.round = Number(button.dataset.round);
            document.querySelectorAll("[data-round]").forEach(item =>
                item.classList.toggle("selected", item === button));
            document.querySelectorAll(".round-label").forEach(item =>
                item.textContent = state.round + "º TURNO");
            await loadData();
        });
    });
    select.addEventListener("change", event => selectUF(event.target.value));
    $("refresh").addEventListener("click", loadData);
    $("ai-explain").addEventListener("click", async () => {
      const button=$("ai-explain"),out=$("ai-explanation");
      const reading=buildElectionReading(state.liveAtlas,{
        uf:state.uf,municipalityCode:state.municipalityCode,
        name:state.municipalityName||(state.uf?UF_NAMES[state.uf]:"Brasil")
      });
      if(!reading||reading.status==="INSUFFICIENT"||!state.liveAtlas){
        out.textContent="Dados oficiais insuficientes para uma explicação assistida.";return;
      }
      const payload={uf:state.uf||"",municipalityCode:state.municipalityCode?String(state.municipalityCode):""};
      button.disabled=true;out.textContent="Consultando modelo externo...";
      try{
        const response=await fetch("/api/v1/ai/explain",{
          method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(payload)
        });
        const result=await response.json();
        if(!response.ok)throw new Error(result.error||"Serviço de IA indisponível");
        out.textContent=result.explanation+"\n\nSíntese automatizada; confira fontes e cálculos originais.";
      }catch(error){out.textContent="Não foi possível gerar a síntese: "+error.message;}
      finally{button.disabled=false;}
    });
    $("archive-search").addEventListener("input", renderArchive);
    $("archive-dataset").addEventListener("change", renderArchive);
    $("archive-refresh").addEventListener("click", loadArchive);
    $("city-search").addEventListener("input", () => {
        renderMunicipalities();
        if(!state.uf && $("city-search").value.trim() && state.hasDbData)ensureNationalCities();
    });
    $("city-rows").addEventListener("keydown",event=>{
        if((event.key==="Enter"||event.key===" ")&&event.target.closest("tr[data-municipality]")){
            event.preventDefault();
            event.target.closest("tr").click();
        }
    });
    $("city-rows").addEventListener("click", async event => {
        const row = event.target.closest("tr[data-municipality]");
        if (!row) return;
        const uf = row.dataset.uf || state.uf;
        const code = row.dataset.municipality;
        let item = findMunicipality(uf,code);
        if (!item) return;
        if (uf !== state.uf) await selectUF(uf);
        item = findMunicipality(uf,code);
        if (!item) return;
        selectMunicipality({uf,code:item.code,name:item.name},true);
        $("map").scrollIntoView({ behavior:"smooth", block:"start" });
    });
    $("clear-city").addEventListener("click", () => {
        state.municipalityCode = null;
        state.municipalityName = null;
        updateRegion();
        updateMapTrail();
        state.mapController?.focusState(state.uf).catch(()=>{});
        renderMetrics();
        renderMunicipalities();
        loadForensicPanels();
        if(state.liveAtlas&&!state.hasDbData)displayLiveTurnout();
    });
    $("run-comparison").addEventListener("click", runComparison);
    document.querySelectorAll(".nav-item").forEach(link => {
        link.addEventListener("click", event => {
            event.preventDefault();
            document.querySelectorAll(".nav-item").forEach(item => item.classList.remove("active"));
            link.classList.add("active");
            $("breadcrumb-view").textContent = link.textContent.trim().toUpperCase();
            const target = link.dataset.view === "overview" ? null : $(link.dataset.view);
            if (target) {
                target.scrollIntoView({ behavior: "smooth", block: "start" });
            } else {
                window.scrollTo({ top: 0, behavior: "smooth" });
            }
        });
    });
}

async function loadData() {
    $("data-warning").hidden = true;
    const results = await Promise.allSettled([
        getJson("/elections/overview?round=" + state.round),
        getJson("/elections/states?round=" + state.round),
        getJson("/sources")
    ]);
    if (results.some(result => result.status === "rejected")) {
        const message = "Não foi possível carregar os dados da API. Verifique a aplicação e o banco de dados.";
        $("data-warning").textContent = message;
        $("data-warning").hidden = false;
        state.error = message;
        state.hasDbData = false;
        state.hasData = false;
        state.overview = null;
        state.states = [];
        state.sources = [];
        await loadOfficialAtlas();
        return;
    }
    state.overview = results[0].value;
    state.states = results[1].value;
    state.sources = results[2].value;
    state.hasData = state.overview.validVotes > 0;
    state.hasDbData = state.hasData;
    nationalCities = null;
    if (state.hasDbData) state.liveAtlas = null;
    state.error = null;
    if (!state.hasData) {
        $("data-warning").textContent =
            "Base ainda não carregada. Este painel não apresenta resultados simulados: importe os CSVs oficiais para iniciar a análise.";
        $("data-warning").hidden = false;
    }
    renderMetrics();
    renderSources();
    await renderReferenceCheck();
    updateRegion();
    renderMap();
    await loadMunicipalities();
    await loadForensicPanels();
    await loadOfficialAtlas();
    renderElectionReading();
}

function renderMetrics() {
    const scoped = selectedRegion();
    $("metric-votes").textContent = scoped?.validVotes ? votes(scoped.validVotes) : "—";
    $("metric-votes-note").textContent = state.municipalityName
        ? state.municipalityName+" · "+state.round+"º turno"
        : state.uf ? UF_NAMES[state.uf]+" · "+state.round+"º turno" : state.round+"º turno · Brasil";
    $("metric-candidates").textContent = scoped?.candidates?.length ? votes(scoped.candidates.length) : "—";
    $("metric-states").textContent = state.uf ? "1" : (state.states.length ? String(state.states.length) : "—");
    $("metric-sources").textContent = state.sources.length ? String(state.sources.length) : "—";
}

function updateRegion() {
    const region = selectedRegion();
    $("region-name").textContent = state.municipalityName || (state.uf ? UF_NAMES[state.uf] : "Brasil");
    $("region-kicker").textContent = state.municipalityName ? "MUNICÍPIO / "+state.uf : state.uf ? "UNIDADE FEDERATIVA / "+state.uf : "RESULTADO NACIONAL";
    $("result-votes").textContent = region?.validVotes ? votes(region.validVotes) : "—";
    if (!region?.validVotes) {
        $("candidate-results").innerHTML = '<div class="empty-result">Nenhum resultado disponível para este recorte e turno.</div>';
        return;
    }
    $("candidate-results").innerHTML = region.candidates.map(candidate => {
        const identity = CANDIDATE_PRESENTATION[candidate.number];
        const color = identity?.color || "#8da1b4";
        const percent = Math.min(100,Math.max(0,Number(candidate.percentage)));
        const portrait = identity?.picture
            ? '<span class="candidate-photo-frame" style="--party-color:'+color+'"><img class="candidate-photo" src="'+
              escapeHtml(identity.picture)+'" alt="Foto de '+escapeHtml(label(candidate))+
              '" loading="lazy" decoding="async" onerror="this.style.display=\'none\'"/>'+
              '<span class="candidate-photo-fallback" aria-hidden="true">'+candidate.number+'</span></span>'
            : '<span class="candidate-number" style="border-color:'+color+'">'+Number(candidate.number)+'</span>';
        return '<div class="candidate" style="--party-color:'+color+'">'+
            '<div class="candidate-heading"><div class="candidate-name">'+portrait+
            '<span class="candidate-title"><strong>'+escapeHtml(label(candidate))+'</strong>'+
            (identity?'<small>'+identity.party+' · '+candidate.number+'</small>':'')+'</span></div>'+
            '<span class="candidate-percentage">'+pct(candidate.percentage)+'</span></div>'+
            '<div class="candidate-votes">'+votes(candidate.votes)+' votos válidos</div>'+
            '<div class="bar-track"><div class="bar-fill" style="width:'+percent+
            '%;background:'+color+'"></div></div></div>';
    }).join("");
    renderElectionReading();
}

// State names and all national cities are indexed from one already-verified TSE
// snapshot. No per-keypress network requests, no forced UF selection.
function findMunicipality(uf,code) {
    if(uf===state.uf) {
        const local=state.municipalities.find(city=>String(city.code)===String(code));
        if(local)return local;
    }
    if(state.hasDbData)return (nationalCities||[]).find(city=>city.uf===uf&&String(city.code)===String(code))||null;
    return state.liveAtlas ? liveMunicipalities(state.round,uf).find(city=>String(city.code)===String(code)) : null;
}
function availableMunicipalities() {
    if(state.uf)return state.municipalities.map(city=>({...city,uf:state.uf}));
    if(state.hasDbData)return nationalCities || [];
    if(!state.liveAtlas)return [];
    return Object.keys(state.liveAtlas.rounds?.[String(state.round)]||{})
        .filter(uf=>UF_NAMES[uf]&&uf!=="ZZ")
        .flatMap(uf=>liveMunicipalities(state.round,uf).map(city=>({...city,uf})));
}
async function loadMunicipalities() {
    state.municipalities=[];
    if(state.liveAtlas&&!state.hasDbData&&state.uf) {
        state.municipalities=liveMunicipalities(state.round,state.uf);
        renderMunicipalities();
        state.mapController?.refresh();
        return;
    }
    if(!state.uf||state.uf==="ZZ"){
        renderMunicipalities();
        return;
    }
    try {
        state.municipalities=await getJson("/elections/states/"+state.uf+
            "/municipalities?round="+state.round);
        renderMunicipalities();
        state.mapController?.refresh();
    } catch(error) {
        $("city-rows").innerHTML='<tr><td colspan="4" class="empty-table">Falha ao carregar os municípios desta UF.</td></tr>';
    }
}
function renderMunicipalities() {
    const term=normalizePlace($("city-search").value);
    const rows=$("city-rows");
    const feedback=$("city-search-feedback");
    if(!state.uf&&!term){
        rows.innerHTML='<tr><td colspan="4" class="empty-table">Digite um município acima para pesquisar em todo o Brasil, ou selecione um estado no mapa.</td></tr>';
        if(feedback)feedback.textContent="Busque em todas as UFs, sem selecionar estado.";
        return;
    }
    const tokens=term.split(" ").filter(Boolean);
    const all=availableMunicipalities();
    if(!state.uf&&term&&state.hasDbData&&!nationalCities){
        if(feedback)feedback.textContent="Indexando municípios das 27 UFs para busca nacional...";
        rows.innerHTML='<tr><td colspan="4" class="empty-table">Preparando índice de municípios...</td></tr>';
        return;
    }
    const matches=all.filter(city=>{
        const haystack=normalizePlace(city.name+" "+city.uf+" "+(UF_NAMES[city.uf]||""));
        return tokens.every(token=>haystack.includes(token));
    });
    matches.sort((a,b)=>a.name.localeCompare(b.name,"pt-BR")||
        a.uf.localeCompare(b.uf));
    const page=matches.slice(0,150);
    if(feedback)feedback.textContent=matches.length
        ?votes(matches.length)+" município(s) encontrado(s)"+
          (matches.length>page.length?" · mostrando os primeiros "+page.length:"")
        :term?"Nenhum município corresponde à pesquisa.":"";
    if(!page.length) {
        rows.innerHTML='<tr><td colspan="4" class="empty-table">'+
            (term?"Nenhum município encontrado. Tente outro nome ou UF.":
            "Não existem municípios disponíveis para este recorte.")+'</td></tr>';
        return;
    }
    rows.innerHTML=page.map(city=>{
        const top=winner(city);
        return '<tr data-municipality="'+escapeHtml(city.code)+
            '" data-uf="'+escapeHtml(city.uf)+
            '" tabindex="0" title="Abrir município no mapa"><td><strong>'+
            escapeHtml(city.name)+'</strong><small class="municipality-uf">'+
            escapeHtml(city.uf)+'</small></td>'+
            '<td>'+votes(city.validVotes)+'</td>'+
            '<td>'+(top?escapeHtml(label(top)):"—")+'</td>'+
            '<td>'+(top?pct(top.percentage):"—")+'</td></tr>';
    }).join("");
}

async function runComparison() {
    const button = $("run-comparison");
    button.disabled = true;
    button.textContent = "Analisando...";
    try {
        const query = new URLSearchParams({
            minVotes: $("min-votes").value,
            thresholdPp: $("threshold").value
        });
        if (state.uf) query.set("uf", state.uf);
        const data = state.liveAtlas && !state.hasDbData
            ? { shifts: liveVoteShifts(query) }
            : await getJson("/elections/comparison?" + query);
        const entries = data.shifts ?? [];
        if (!entries.length) {
            $("comparison-rows").innerHTML = '<tr><td colspan="5" class="empty-table">Nenhuma variação encontrada nos dados disponíveis com estes filtros. Isso não constitui prova de integridade ou de ausência de fraude.</td></tr>';
            return;
        }
        $("comparison-rows").innerHTML = entries.map(entry => {
            const change = Number(entry.changePp);
            const value = (change > 0 ? "+" : "") + percentageFormat.format(change);
            return '<tr><td>' + escapeHtml(entry.uf + " · " + entry.municipality) + '</td>' +
                '<td>' + escapeHtml(LABELS[entry.candidateNumber] ?? ("Votável " + entry.candidateNumber)) + '</td>' +
                '<td>' + votes(entry.firstVotes) + '</td><td>' + votes(entry.secondVotes) + '</td>' +
                '<td class="' + (change >= 0 ? "change-positive" : "change-negative") + '">' +
                value + ' p.p.</td></tr>';
        }).join("");
    } catch (error) {
        $("comparison-rows").innerHTML = '<tr><td colspan="5" class="empty-table">Não foi possível executar a comparação.</td></tr>';
    } finally {
        button.disabled = false;
        button.textContent = "Comparar turnos ↗";
    }
}

async function renderReferenceCheck() {
    const target = $("national-reference-check");
    if (!target) return;
    try {
        const result = await getJson("/audits/national-reference");
        const status = result.status;
        let title = "COBERTURA INCOMPLETA";
        let description = result.missingUfs.length + " unidades ainda sem dados completos; a comparação não pode ser interpretada como divergência.";
        if (status === "CONFERE_COM_TOTAL_PUBLICADO") {
            title = "TOTAIS CONFEREM";
            description = "Os agregados importados coincidem com os totais nacionais divulgados pelo TSE em 26/10/2026. Isso não comprova integridade do software das urnas.";
        } else if (status === "DIVERGENCIA_COM_TOTAL_PUBLICADO") {
            title = "DIFERENÇA A INVESTIGAR";
            description = "Com cobertura territorial completa, os valores importados diferem da referência publicada. Verifique escopo, fontes e transformações antes de atribuir causa.";
        }
        target.innerHTML = '<span class="reference-symbol">◎</span><div><strong>' + title +
            '</strong><p>' + escapeHtml(description) + '</p></div><a href="' +
            escapeHtml(result.referenceUrl) +
            '" rel="noopener noreferrer" target="_blank">REFERÊNCIA TSE ↗</a>';
    } catch (error) {
        target.textContent = "Verificação da referência oficial não disponível no momento.";
    }
}

function renderSources() {
    if (!state.sources.length) {
        $("sources-list").innerHTML = '<div class="empty-result">Nenhum arquivo importado. A base não pode ser considerada auditada.</div>';
        return;
    }
    $("sources-list").innerHTML = state.sources.map(source =>
        '<div class="source-item"><strong>' + escapeHtml(source.filename) + '</strong>' +
        '<small>SHA-256 ' + escapeHtml(source.sha256) + '</small>' +
        '<small>' + votes(source.rows) + ' linhas presidenciais · ' +
        escapeHtml(source.importedAt) + '</small></div>').join("");
}

// All map geometries are self-hosted in /geo: no runtime IBGE API dependency.
async function loadGeometry() {
    state.mapController = createAtlasMap({
        onState: uf => selectUF(uf),
        onCity: city => selectMunicipality(city,false),
        onReset: () => selectUF(""),
        getCities: () => state.municipalities,
        getStates: () => state.states,
        tooltip: $("map-tooltip")
    });
    $("map-zoom-in").addEventListener("click",()=>state.mapController.zoom(1.65));
    $("map-zoom-out").addEventListener("click",()=>state.mapController.zoom(1/1.65));
    $("map-reset").addEventListener("click",()=>selectUF(""));
    try {
        await state.mapController.boot();
        $("state-fallback").hidden = true;
        if (state.uf) await state.mapController.focusState(state.uf);
        updateMapTrail();
    } catch (error) {
        $("map-canvas").textContent = "Não foi possível abrir a malha cartográfica local. Use o seletor de UF.";
        const fallback = $("state-fallback");
        fallback.hidden = false;
        fallback.replaceChildren();
        Object.keys(UF_NAMES).filter(uf=>uf!=="ZZ").forEach(uf=>{
            const button=document.createElement("button");
            button.type="button";button.textContent=uf;
            button.addEventListener("click",()=>selectUF(uf));
            fallback.append(button);
        });
    }
}
function updateMapTrail(){
    const trail=$("map-trail");
    if(!trail)return;
    trail.textContent="BRASIL"+(state.uf?"  /  "+state.uf:"")+
        (state.municipalityName?"  /  "+state.municipalityName.toUpperCase():"");
    $("map-reset").hidden=!state.uf;
}
async function selectUF(uf) {
    const safe=uf&&UF_NAMES[uf]?uf:"";
    state.uf=safe;state.municipalityCode=null;state.municipalityName=null;
    $("uf-select").value=safe;
    updateMapTrail();
    await loadMunicipalities();
    updateRegion();
    renderMetrics();
    try {await state.mapController?.focusState(safe);}
    catch(error){$("data-warning").textContent="A malha municipal deste estado não pôde ser carregada; os dados eleitorais permanecem acessíveis na listagem.";$("data-warning").hidden=false;}
    await loadForensicPanels();
    if(state.liveAtlas&&!state.hasDbData)displayLiveTurnout();
}
function selectMunicipality(city,moveMap) {
    state.uf=city.uf;state.municipalityCode=city.code;
    state.municipalityName=city.name;
    updateRegion();updateMapTrail();renderMetrics();
    if(moveMap) {
        const focused=state.mapController?.focusCityByCode(city.code,city.name);
        if(!focused) {
            $("map-tooltip").hidden=false;
            $("map-tooltip").textContent="Dados municipais selecionados. Contorno geográfico não identificado com segurança.";
        }
    }
    loadForensicPanels();
    if(state.liveAtlas&&!state.hasDbData)displayLiveTurnout();
}
function renderMap(){state.mapController?.refresh();}

setup();
const initialDataLoad = loadData();
loadGeometry();

async function loadForensicPanels() {
    const filters = new URLSearchParams();
    if (state.uf) filters.set("uf", state.uf);
    if (state.uf && state.municipalityCode) filters.set("municipalityCode", state.municipalityCode);
    const query = filters.size ? "?" + filters : "";
    $("audit-scope").textContent = "Recorte: " + (state.municipalityName ||
        (state.uf ? UF_NAMES[state.uf] : "Brasil"));
    $("clear-city").hidden = !state.municipalityCode;
    const getSafe = async path => {
        try { return await getJson(path + query); }
        catch (e) { return null; }
    };
    const [turnout, biometric, bweb, shifts] = await Promise.all([
        getSafe("/audits/turnout/comparison"),
        getSafe("/audits/biometrics/comparison"),
        getSafe("/audits/biometrics/bweb/comparison"),
        state.uf ? getSafe("/audits/turnout/section-shifts") : Promise.resolve(null)
    ]);
    const display = (data, prefix, quantityField, detailField, changeKey) => {
        if (!data) {
            $(prefix + "-status").textContent = "API de auditoria indisponível.";
            return;
        }
        const first = data.first, second = data.second;
        const firstLoaded = first.sections > 0, secondLoaded = second.sections > 0;
        $(prefix + "-first").textContent = firstLoaded ? pct(first[quantityField]) : "—";
        $(prefix + "-second").textContent = secondLoaded ? pct(second[quantityField]) : "—";
        $(prefix + "-first-info").textContent = firstLoaded ? votes(first[detailField]) + " seções" : "Sem dados";
        $(prefix + "-second-info").textContent = secondLoaded ? votes(second[detailField]) + " seções" : "Sem dados";
        const change = data[changeKey];
        $(prefix + "-delta").textContent = change == null ?
            "Dados incompletos para comparar turnos." :
            (Number(change) > 0 ? "+" : "") + percentageFormat.format(Number(change)) +
            " p.p. de variação (descrição, não comprovação de fraude)";
        $(prefix + "-status").textContent = data.status === "DESCRIPTIVE_ONLY" ?
            "Comparação descritiva disponível — verifique cobertura e fontes." :
            "Sem cobertura de ambos os turnos.";
    };
    display(turnout, "turnout", "abstentionPercentage", "sections", "abstentionChangePp");
    const target = $("section-changes-list");
    if (target) {
        const items = shifts?.findings ?? [];
        target.innerHTML = !state.uf ? "Selecione uma UF para comparar seções." :
            !items.length ? "Nenhuma seção encontrada para o recorte ou dados ainda indisponíveis." :
            items.slice(0,20).map(item => '<div class="section-change-item"><span>' +
                escapeHtml(item.municipality) + ' · Z' + Number(item.zone) + '/S' + Number(item.section) +
                '</span><strong>' + (Number(item.abstentionChangePp)>0?'+':'') +
                percentageFormat.format(Number(item.abstentionChangePp)) + ' p.p.</strong></div>').join("");
    }
    display(biometric, "biometric", "nonBiometricPercentage", "sections", "nonBiometricChangePp");
    if (bweb) {
        for (const [period, record] of [["first", bweb.first], ["second", bweb.second]]) {
            $("bweb-" + period).textContent = record.sections ? pct(record.percentage) : "—";
            $("bweb-" + period + "-info").textContent = record.sections ?
                votes(record.notRecognized) + " habilitações (" + votes(record.sections) + " seções)" : "Sem dados oficiais";
        }
        $("bweb-delta").textContent = bweb.changePp == null ?
            "Dados BWEB incompletos entre os turnos." :
            (Number(bweb.changePp) > 0 ? "+" : "") + percentageFormat.format(Number(bweb.changePp)) +
            " p.p. de variação nas falhas de reconhecimento biométrico.";
    } else {
        $("bweb-delta").textContent = "API BWEB indisponível.";
    }
    if (state.liveAtlas && !state.hasDbData) displayLiveTurnout();
}

async function loadArchive() {
    const element = $("archive-rows");
    try {
        const index = await getJson("/archive/history");
        state.archive = index;
        $("archive-last-poll").textContent = index.last_polled_at ?
            new Date(index.last_polled_at).toLocaleString("pt-BR", { timeZone:"America/Sao_Paulo" }) : "Sem execução";
        $("archive-version-count").textContent = numberFormat.format(index.snapshots?.length ?? 0);
        $("archive-original-count").textContent = numberFormat.format(
            index.snapshots?.filter(item => item.original?.status === "stored").length ?? 0);
        renderArchive();
    } catch (error) {
        element.innerHTML = '<tr><td colspan="5" class="empty-table">Não foi possível consultar a lista de versões. O histórico armazenado no Git não foi alterado.</td></tr>';
    }
}
function renderArchive() {
    const index = state.archive;
    const term = $("archive-search").value.trim().toLocaleLowerCase("pt-BR");
    const filter = $("archive-dataset").value;
    const snapshots = (index?.snapshots || []).filter(item =>
        (!filter || item.dataset === filter) &&
        ((!term || [item.name,item.resource_id,item.dataset].some(value =>
            String(value || "").toLocaleLowerCase("pt-BR").includes(term))))
    ).slice().reverse().slice(0, 150);
    if (!snapshots.length) {
        $("archive-rows").innerHTML = '<tr><td colspan="5" class="empty-table">' +
            (index?.poll_count ? "Nenhum histórico encontrado para esse filtro." :
                "Sem primeira consulta concluída. O sincronizador será executado pelo GitHub Actions.") +
            '</td></tr>';
        return;
    }
    $("archive-rows").innerHTML = snapshots.map(item => {
        const original = item.original?.status === "stored";
        const officialUrl = String(item.source_url || "");
        const source = officialUrl.startsWith("https://") ? officialUrl : "";
        const snapshotPath = String(item.snapshot || "");
        const snapshotUrl = "https://github.com/luciano-mota/tse-forensic-investigation/blob/archive-history/archive/" + snapshotPath.split("/").map(encodeURIComponent).join("/");
        const safeTime = item.observed_at ?
            new Date(item.observed_at).toLocaleString("pt-BR",{timeZone:"America/Sao_Paulo"}) : "—";
        const downloadUrl = "/api/v1/archive/original?snapshot=" + encodeURIComponent(snapshotPath);
        const actions = (original ? '<a class="archive-action" href="' + downloadUrl + '">Baixar cópia ↗</a>' :
                '<span class="not-saved">Sem cópia própria</span>') +
            (source ? '<a class="archive-action" href="' + escapeHtml(source) +
                '" target="_blank" rel="noopener noreferrer">Fonte TSE ↗</a>' : '');
        return '<tr><td><strong>' + escapeHtml(item.name) +
            '</strong><div class="archive-path">' + escapeHtml(item.dataset) + '</div></td>' +
            '<td>' + escapeHtml(safeTime) + '</td>' +
            '<td><a class="archive-action" href="' + escapeHtml(snapshotUrl) +
            '" target="_blank" rel="noopener noreferrer">Ver metadados ↗</a></td>' +
            '<td><span class="' + (original?'archived-indicator':'metadata-indicator') + '">' +
            (original?'Original preservado':'Apenas metadados') + '</span></td>' +
            '<td class="archive-actions">' + actions + '</td></tr>';
    }).join("");
}
loadArchive();

/**
 * The drifting index highlights the current investigation area as the user reads.
 * It is a set of anchors, not a second routing system or a hidden drawer.
 */
function enableDriftIndex() {
    const items = Array.from(document.querySelectorAll(".drift-index .nav-item"));
    const targets = items.map(item => ({
        item,
        target: item.dataset.view === "overview"
            ? document.getElementById("overview")
            : document.getElementById(item.dataset.view)
    })).filter(pair => pair.target);
    if (!("IntersectionObserver" in window)) return;
    const observer = new IntersectionObserver(entries => {
        const visible = entries.filter(entry => entry.isIntersecting)
            .sort((a, b) => b.intersectionRatio - a.intersectionRatio);
        if (!visible.length) return;
        const current = targets.find(pair => pair.target === visible[0].target);
        if (!current) return;
        items.forEach(item => {
            const selected = item === current.item;
            item.classList.toggle("active", selected);
            if (selected) item.setAttribute("aria-current", "location");
            else item.removeAttribute("aria-current");
        });
    }, { rootMargin: "-18% 0px -45% 0px", threshold: [0, 0.05, 0.2, 0.5] });
    targets.forEach(pair => observer.observe(pair.target));
    document.addEventListener("keydown", event => {
        const tag = document.activeElement?.tagName;
        if (event.key !== "/" || event.ctrlKey || event.metaKey || event.altKey ||
            ["INPUT", "TEXTAREA", "SELECT"].includes(tag)) return;
        event.preventDefault();
        const search = document.getElementById("city-search");
        search.scrollIntoView({ behavior: "smooth", block: "center" });
        search.focus({ preventScroll: true });
    });
}
enableDriftIndex();


/**
 * Storage-free, transparently versioned mirror.
 * The GitHub Actions runner streams the *original official TSE files* into temporary
 * files; only independently checkable aggregates + sha256 remain in public history.
 */
function liveRegion(code, name, candidates) {
    const results = Object.entries(candidates || {}).map(([number,count]) =>
        ({ number:Number(number),name:LABELS[Number(number)] || ("Votável " + number),
           votes:Number(count),percentage:0 })).filter(c=>Number.isFinite(c.votes) && c.votes>=0);
    const validVotes = results.reduce((n,c)=>n+c.votes,0);
    for(const result of results) result.percentage=validVotes?100*result.votes/validVotes:0;
    results.sort((a,b)=>b.votes-a.votes);
    return {code,name,validVotes,candidates:results};
}
function liveState(round, uf) {
    return state.liveAtlas?.rounds?.[String(round)]?.[uf] || null;
}
function liveMunicipalities(round, uf) {
    const original=liveState(round,uf)?.municipalities || {};
    return Object.entries(original).map(([code,city])=>
        liveRegion(code,city.name,city.candidates)).sort((a,b)=>a.name.localeCompare(b.name,"pt-BR"));
}
function liveAggregate(round, uf, municipalityCode) {
    const data=state.liveAtlas?.rounds?.[String(round)] || {};
    const regions=uf?(data[uf]?[data[uf]]:[]):Object.values(data);
    let registered=0,turnout=0,abstentions=0,sections=0;
    for(const region of regions) {
        const municipalities=municipalityCode
            ? (region.municipalities?.[municipalityCode]?[region.municipalities[municipalityCode]]:[])
            : Object.values(region.municipalities || {});
        for(const municipal of municipalities) {
            if (!municipal.turnout) continue;
            registered+=municipal.turnout.registered;
            turnout+=municipal.turnout.turnout;
            abstentions+=municipal.turnout.abstentions;
            sections++;
        }
    }
    return {registered,turnout,abstentions,sections,rate:registered?100*abstentions/registered:null};
}
function displayLiveTurnout() {
    const one=liveAggregate(1,state.uf,state.municipalityCode);
    const two=liveAggregate(2,state.uf,state.municipalityCode);
    for(const [key,item] of [["first",one],["second",two]]) {
        $("turnout-"+key).textContent=item.rate===null?"—":pct(item.rate);
        $("turnout-"+key+"-info").textContent=item.sections
            ? votes(item.abstentions)+" abstenções em "+votes(item.sections)+" municípios"
            : "Cobertura indisponível";
    }
    $("turnout-delta").textContent=(one.rate==null||two.rate==null)
        ? "Cobertura insuficiente para comparar turnos."
        : ((two.rate-one.rate)>0?"+":"")+percentageFormat.format(two.rate-one.rate)+
          " p.p. · comparação descritiva";
    const mismatches=Number(state.liveAtlas?.parse_stats?.turnout?.arithmetic_mismatches||0);
    $("turnout-status").textContent="Síntese oficial por município/zona. Não é conferência por seção."+
        (mismatches>0?" "+votes(mismatches)+" registros com igualdade aritmética pendente de reconciliação; não indicam fraude por si.":"");
    $("section-changes-list").textContent="A comparação por seção exige o arquivo oficial de apuração por seção; este espelho inicial tem granularidade municipal.";
}
function liveVoteShifts(query) {
    const minVotes=Number(query.get("minVotes")||500),threshold=Number(query.get("thresholdPp")||15);
    const a=state.liveAtlas?.rounds?.["1"]||{},b=state.liveAtlas?.rounds?.["2"]||{};
    const ufs=query.get("uf")?[query.get("uf")]:Object.keys(b);
    const changes=[];
    for(const uf of ufs) {
        const before=a[uf]?.municipalities||{},after=b[uf]?.municipalities||{};
        for(const [code,city] of Object.entries(after)) {
            const prev=before[code];
            if(!prev) continue;
            const first=liveRegion(code,prev.name,prev.candidates);
            const last=liveRegion(code,city.name,city.candidates);
            if(first.validVotes<minVotes||last.validVotes<minVotes)continue;
            for(const cand of [13,22]) {
                const firstVotes=Number(prev.candidates?.[String(cand)]||0);
                const secondVotes=Number(city.candidates?.[String(cand)]||0);
                const changePp=100*secondVotes/last.validVotes-100*firstVotes/first.validVotes;
                if(Math.abs(changePp)<threshold)continue;
                changes.push({uf,municipality:city.name,municipalityCode:code,
                    candidateNumber:cand,firstVotes,secondVotes,changePp});
            }
        }
    }
    return changes.sort((a,b)=>Math.abs(b.changePp)-Math.abs(a.changePp)).slice(0,150);
}
async function loadOfficialAtlas() {
    if(state.hasDbData || state.loadingOfficialAtlas) return;
    state.loadingOfficialAtlas = true;
    try{
        const live=await getJson("/live/atlas");
        if(!live.rounds||live.status!=="DERIVED_FROM_OFFICIAL_FILES_NOT_ORIGINAL_ARCHIVE"){
            $("data-warning").textContent=live.warning ||
              "Ainda não há uma leitura integral dos arquivos nacionais do TSE validada pelo pipeline.";
            $("data-warning").hidden=false;
            return;
        }
        state.liveAtlas=live;
        state.states=Object.entries(live.rounds[String(state.round)]||{}).map(([uf,region])=>
            liveRegion(uf,UF_NAMES[uf]||uf,region.candidates||{})).filter(r=>r.validVotes>0);
        const national={};
        for(const region of state.states)
            for(const cand of region.candidates)national[cand.number]=(national[cand.number]||0)+cand.votes;
        state.overview=liveRegion("BR","Brasil",national);
        state.hasData=state.overview.validVotes>0;
        state.sources=Object.entries(live.sources||{}).map(([kind,source])=>({
            filename:(kind==="votes"?"Votação por candidato":"Comparecimento")+" · "+(source.url||""),
            sha256:source.sha256||"",rows:live.parse_stats?.[kind]?.president_rows||0,
            importedAt:live.observed_at||""
        }));
        $("data-warning").hidden=true;
        const date=live.observed_at?new Date(live.observed_at).toLocaleString("pt-BR",{
            timeZone:"America/Sao_Paulo"}):"horário desconhecido";
        const ref=$("national-reference-check");
        const check=live.national_reference?.status==="MATCHES_TSE_PUBLISHED_TOTALS"
            ? "2º turno conferido com os totais nacionais publicados pelo TSE."
            : "Referência nacional ainda não conciliada. Verifique cobertura e transformações.";
        ref.textContent=check+" Leitura "+date+
            " · verificação periódica, não transmissão de votos em tempo real · originais não arquivados.";
        renderMetrics();
        $("metric-sources").textContent=String(state.sources.length);
        renderSources();
        updateRegion();
        await loadMunicipalities();
        renderMap();
        updateRegion();
        renderMetrics();
        displayLiveTurnout();
    }catch(error){
        $("data-warning").textContent="O espelho de totais oficiais está indisponível; nenhum resultado foi inferido.";
        $("data-warning").hidden=false;
    }finally{
        state.loadingOfficialAtlas = false;
    }
}


// "Automatic" means the page notices newly processed official snapshots.
// The TSE's 2026 public dataset is not a continuous individual-vote feed.
const liveTimestamp = value => {
    if (!value) return "Ainda não disponível";
    const date = new Date(value);
    return Number.isFinite(date.getTime())
        ? date.toLocaleString("pt-BR",{timeZone:"America/Sao_Paulo",
            day:"2-digit",month:"2-digit",hour:"2-digit",minute:"2-digit"})
        : "Horário indisponível";
};
function renderSourceMonitor(data) {
    $("live-last-check").textContent = liveTimestamp(data.checkedAt);
    $("live-snapshot-time").textContent = liveTimestamp(data.snapshotAt);
    const messages = {
        OK:"Consulta concluída · dados oficiais",
        PENDING:"Aguardando primeiro processamento",
        STALE:"Última consulta atrasada",
        DEGRADED:"Falha na coleta · versão anterior preservada",
        ERROR:"A primeira extração não foi concluída",
        UNAVAILABLE:"Status de consulta indisponível"
    };
    const element=$("live-update-status");
    element.textContent=messages[data.status]||"Status desconhecido";
    element.dataset.status=data.status||"UNKNOWN";
    if (data.warning) element.title=data.warning;
    else element.removeAttribute("title");
}
let statusPollingInProgress=false;
async function pollOfficialStatus() {
    if (statusPollingInProgress || document.visibilityState==="hidden") return;
    statusPollingInProgress=true;
    try {
        const source=await getJson("/live/status");
        state.lastSourceStatus=source;
        renderSourceMonitor(source);
        if (!state.hasDbData && source.version &&
            source.version!==state.liveAtlas?.derived_version && !state.loadingOfficialAtlas) {
            await loadOfficialAtlas();
        }
    }catch(error) {
        renderSourceMonitor({
            status:"UNAVAILABLE",
            checkedAt:state.lastSourceStatus?.checkedAt,
            snapshotAt:state.lastSourceStatus?.snapshotAt
        });
    }finally {
        statusPollingInProgress=false;
    }
}
function startAutomaticSourceChecks() {
    pollOfficialStatus();
    // Only refresh when visible; never repeat the full 2 MB atlas download
    // if its version did not change.
    window.setInterval(pollOfficialStatus,120000);
    document.addEventListener("visibilitychange",() => {
        if (document.visibilityState==="visible") pollOfficialStatus();
    });
}
Promise.resolve(initialDataLoad).catch(()=>{}).finally(startAutomaticSourceChecks);



/**
 * A sourced, deterministic reading. Not a model-generated accusation or inference.
 * Scope changes (country/UF/municipality) rerun the exact same published formulas.
 */
function renderElectionReading() {
    const content=$("analysis-content");
    const status=$("analysis-status");
    if(!content||!status)return;
    const download=$("report-pdf");
    if(download) {
        const p=new URLSearchParams();
        if(state.uf)p.set("uf",state.uf);
        if(state.municipalityCode)p.set("municipalityCode",state.municipalityCode);
        download.href="/api/v1/reports/presidential-2026.pdf"+(p.size?"?"+p:"");
        download.download="tse-forensic-2026-"+(state.uf||"brasil")+
            (state.municipalityCode?"-"+state.municipalityCode:"")+".pdf";
        download.hidden=!state.liveAtlas;
    }
    const reading=buildElectionReading(state.liveAtlas,{
        uf:state.uf,municipalityCode:state.municipalityCode,
        name:state.municipalityName||(state.uf?UF_NAMES[state.uf]:"Brasil")
    });
    if(!reading || reading.status==="INSUFFICIENT") {
        status.textContent=reading?.headline||"Aguardando importação e validação dos arquivos oficiais.";
        content.textContent=reading?.explanation||"O sistema não publica conclusões sem dados suficientes nos dois turnos.";
        return;
    }
    status.textContent="LEITURA DESCRITIVA · "+reading.name.toUpperCase()+
        " · ARQUIVOS TSE VALIDADOS POR SHA-256";
    const rows=reading.calculations.map(calc=>'<tr><td><strong>'+escapeHtml(calc.label)+
        '</strong><span class="analysis-formula">'+escapeHtml(calc.formula)+'</span></td>'+
        '<td class="analysis-expression">'+escapeHtml(calc.expression)+'</td>'+
        '<td class="analysis-result">'+escapeHtml(calc.result)+'</td></tr>').join("");
    const bullets=reading.interpretations.map(item=>'<li>'+escapeHtml(item)+'</li>').join("");
    const checks=reading.checks.map(check=>'<div class="evidence-observation evidence-'+
        escapeHtml(check.kind)+'"><strong>'+escapeHtml(check.label)+'</strong><p>'+
        escapeHtml(check.detail)+'</p></div>').join("");
    const sources=reading.sources.map(src=>'<li><a href="'+escapeHtml(src.url)+
        '" target="_blank" rel="noopener noreferrer">'+escapeHtml(src.filename)+'</a>'+
        '<code>SHA-256 '+escapeHtml(src.sha256)+'</code></li>').join("");
    content.innerHTML='<div class="analysis-story"><h3>'+escapeHtml(reading.headline)+'</h3>'+
        '<p class="analysis-lead">'+escapeHtml(reading.lead)+'</p>'+
        '<ul class="analysis-findings">'+bullets+'</ul></div>'+
        '<div class="analysis-formula-section"><h4>Como os números foram calculados</h4>'+
        '<div class="table-scroll"><table><thead><tr><th>INDICADOR / FÓRMULA</th>'+
        '<th>SUBSTITUIÇÃO</th><th>RESULTADO</th></tr></thead><tbody>'+rows+
        '</tbody></table></div></div>'+
        '<div class="analysis-integrity"><h4>Checagens de consistência e limites</h4>'+
        checks+'<p class="analysis-caution">'+escapeHtml(reading.caveat)+'</p>'+
        (reading.referenceNote?'<p>'+escapeHtml(reading.referenceNote)+'</p>':'')+'</div>'+
        '<details class="analysis-method"><summary>Método reproduzível e origem dos dados</summary>'+
        '<p>'+escapeHtml(reading.methodology)+'</p>'+
        '<p>Snapshot observado em '+escapeHtml(reading.observedAt||"data não informada")+
        '; versão derivada <code>'+escapeHtml(reading.version||"—")+'</code>.</p>'+
        '<ul>'+sources+'</ul><p><a href="https://dadosabertos.tse.jus.br/dataset/resultados-2026" '+
        'target="_blank" rel="noopener noreferrer">Portal de Dados Abertos do TSE ↗</a></p></details>';
}
