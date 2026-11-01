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
const LABELS = { 13: "Lula (PT)", 22: "Flávio Bolsonaro (PL)" };
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
    error: null,
    hasData: false,
    archive: null
};

const $ = id => document.getElementById(id);
const escapeHtml = value => String(value ?? "").replace(/[&<>"']/g, char => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;"
}[char]));
const votes = value => numberFormat.format(value ?? 0);
const pct = value => percentageFormat.format(Number(value ?? 0)) + "%";
const winner = region => region?.candidates?.[0] ?? null;
const label = candidate => LABELS[candidate.number] ||
    (candidate.name?.startsWith("Votável ") ? "Votável " + candidate.number : candidate.name);
const selectedRegion = () => state.uf
    ? state.states.find(region => region.code === state.uf)
    : state.overview;

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
    select.addEventListener("change", async event => {
        state.uf = event.target.value;
        state.municipalityCode = null;
        state.municipalityName = null;
        updateRegion();
        renderMap();
        await loadMunicipalities();
    await loadForensicPanels();
    });
    $("refresh").addEventListener("click", loadData);
    $("archive-search").addEventListener("input", renderArchive);
    $("archive-dataset").addEventListener("change", renderArchive);
    $("archive-refresh").addEventListener("click", loadArchive);
    $("city-search").addEventListener("input", renderMunicipalities);
    $("city-rows").addEventListener("click", event => {
        const row = event.target.closest("tr[data-municipality]");
        if (!row) return;
        const item = state.municipalities.find(city => city.code === row.dataset.municipality);
        if (!item) return;
        state.municipalityCode = item.code;
        state.municipalityName = item.name;
        loadForensicPanels();
        $("turnout").scrollIntoView({ behavior:"smooth", block:"start" });
    });
    $("clear-city").addEventListener("click", () => {
        state.municipalityCode = null;
        state.municipalityName = null;
        loadForensicPanels();
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
        return;
    }
    state.overview = results[0].value;
    state.states = results[1].value;
    state.sources = results[2].value;
    state.hasData = state.overview.validVotes > 0;
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
}

function renderMetrics() {
    $("metric-votes").textContent = state.hasData ? votes(state.overview.validVotes) : "—";
    $("metric-votes-note").textContent = state.hasData ? state.round + "º turno · Brasil" : "Sem arquivos importados";
    $("metric-candidates").textContent = state.hasData ? votes(state.overview.candidates.length) : "—";
    $("metric-states").textContent = state.states.length ? String(state.states.length) : "—";
    $("metric-sources").textContent = state.sources.length ? String(state.sources.length) : "—";
}

function updateRegion() {
    const region = selectedRegion();
    $("region-name").textContent = state.uf ? UF_NAMES[state.uf] : "Brasil";
    $("region-kicker").textContent = state.uf ? "UNIDADE FEDERATIVA / " + state.uf : "RESULTADO NACIONAL";
    $("result-votes").textContent = region?.validVotes ? votes(region.validVotes) : "—";
    if (!region?.validVotes) {
        $("candidate-results").innerHTML = '<div class="empty-result">Nenhum resultado disponível para este recorte e turno.</div>';
        return;
    }
    $("candidate-results").innerHTML = region.candidates.map(candidate => {
        const color = candidate.number === 13 ? "var(--purple)" :
            candidate.number === 22 ? "var(--accent)" : "#a2b6c8";
        const safePct = Math.min(100, Math.max(0, Number(candidate.percentage)));
        return '<div class="candidate">' +
            '<div class="candidate-heading"><div class="candidate-name">' +
            '<span class="candidate-number">' + candidate.number + '</span>' +
            escapeHtml(label(candidate)) + '</div>' +
            '<span class="candidate-percentage">' + pct(candidate.percentage) + '</span></div>' +
            '<div class="candidate-votes">' + votes(candidate.votes) + ' votos válidos</div>' +
            '<div class="bar-track"><div class="bar-fill" style="width:' + safePct + '%;background:' + color + '"></div></div></div>';
    }).join("");
}

async function loadMunicipalities() {
    state.municipalities = [];
    if (!state.uf || state.uf === "ZZ") {
        renderMunicipalities();
        return;
    }
    try {
        state.municipalities = await getJson("/elections/states/" + state.uf +
            "/municipalities?round=" + state.round);
        renderMunicipalities();
    } catch (error) {
        $("city-rows").innerHTML = '<tr><td colspan="4" class="empty-table">Falha ao carregar os municípios.</td></tr>';
    }
}

function renderMunicipalities() {
    const term = $("city-search").value.trim().toLocaleLowerCase("pt-BR");
    const list = state.municipalities.filter(region =>
        region.name.toLocaleLowerCase("pt-BR").includes(term)).slice(0, 200);
    if (list.length === 0) {
        $("city-rows").innerHTML = '<tr><td colspan="4" class="empty-table">' +
            (state.uf ? "Nenhum município encontrado neste recorte." : "Selecione uma UF para consultar municípios.") +
            '</td></tr>';
        return;
    }
    $("city-rows").innerHTML = list.map(region => {
        const top = winner(region);
        return '<tr data-municipality="' + escapeHtml(region.code) + '" title="Selecionar município para auditoria"><td><strong>' + escapeHtml(region.name) + '</strong></td>' +
            '<td>' + votes(region.validVotes) + '</td>' +
            '<td>' + (top ? escapeHtml(label(top)) : "—") + '</td>' +
            '<td>' + (top ? pct(top.percentage) : "—") + '</td></tr>';
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
        const data = await getJson("/elections/comparison?" + query);
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

async function loadGeometry() {
    try {
        const [module, response] = await Promise.all([
            import("https://cdn.jsdelivr.net/npm/d3-geo@3/+esm"),
            fetch("https://servicodados.ibge.gov.br/api/v3/malhas/estados?formato=application/vnd.geo%2Bjson&qualidade=minima")
        ]);
        if (!response.ok) throw new Error("IBGE " + response.status);
        const geo = await response.json();
        if (geo.type !== "FeatureCollection" || !Array.isArray(geo.features)) {
            throw new Error("Geometria territorial inválida.");
        }
        state.geoModule = module;
        state.mapFeatures = geo;
        renderMap();
    } catch (error) {
        $("map-canvas").innerHTML = '<p class="map-loading">Geometria do IBGE indisponível. Selecione uma UF abaixo.</p>';
        $("state-fallback").hidden = false;
        $("state-fallback").innerHTML = Object.keys(UF_NAMES).filter(uf => uf !== "ZZ")
            .map(uf => '<button data-uf="' + uf + '" type="button">' + uf + '</button>').join("");
        $("state-fallback").querySelectorAll("[data-uf]").forEach(button =>
            button.addEventListener("click", () => selectUF(button.dataset.uf)));
    }
}

function geometryUF(feature) {
    const properties = feature.properties || {};
    const code = String(feature.id || properties.codarea || properties.CD_UF || "").slice(0, 2);
    return (properties.sigla || properties.SIGLA || IBGE_CODES[code] || "").toUpperCase();
}

function selectUF(uf) {
    state.uf = uf;
    $("uf-select").value = uf;
    updateRegion();
    renderMap();
    loadMunicipalities();
    loadForensicPanels();
}

function renderMap() {
    if (!state.mapFeatures || !state.geoModule) return;
    const { geoPath, geoMercator } = state.geoModule;
    const projection = geoMercator().fitExtent([[24, 22], [715, 460]], state.mapFeatures);
    const path = geoPath(projection);
    const ns = "http://www.w3.org/2000/svg";
    const svg = document.createElementNS(ns, "svg");
    svg.setAttribute("viewBox", "0 0 740 485");
    svg.setAttribute("role", "img");
    svg.setAttribute("aria-label", "Mapa interativo dos estados brasileiros com resultados do turno selecionado");
    for (const feature of state.mapFeatures.features) {
        const uf = geometryUF(feature);
        if (!UF_NAMES[uf]) continue;
        const region = state.states.find(item => item.code === uf);
        const top = winner(region);
        const fill = !top ? "#263d50" : top.number === 13 ? "#9d82db" :
            top.number === 22 ? "#5cbfc5" : "#617a90";
        const shape = document.createElementNS(ns, "path");
        shape.setAttribute("d", path(feature) || "");
        shape.setAttribute("fill", fill);
        shape.setAttribute("class", "map-path");
        shape.setAttribute("tabindex", "0");
        shape.setAttribute("aria-label", (UF_NAMES[uf] || uf) + (region ? ", " + votes(region.validVotes) + " votos válidos" : ", sem dados"));
        if (uf === state.uf) {
            shape.setAttribute("stroke", "#fff");
            shape.setAttribute("stroke-width", "2.4");
        }
        const showTooltip = () => {
            const tooltip = $("map-tooltip");
            tooltip.hidden = false;
            tooltip.innerHTML = '<strong>' + escapeHtml(UF_NAMES[uf]) + '</strong><small>' +
                (top ? escapeHtml(label(top)) + ' · ' + pct(top.percentage) : "Sem dados importados") +
                '</small>';
        };
        shape.addEventListener("mouseenter", showTooltip);
        shape.addEventListener("focus", showTooltip);
        shape.addEventListener("mouseleave", () => $("map-tooltip").hidden = true);
        shape.addEventListener("blur", () => $("map-tooltip").hidden = true);
        shape.addEventListener("click", () => selectUF(uf));
        shape.addEventListener("keydown", event => {
            if (event.key === "Enter" || event.key === " ") {
                event.preventDefault();
                selectUF(uf);
            }
        });
        svg.appendChild(shape);
    }
    $("map-canvas").replaceChildren(svg);
}

setup();
loadData();
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
