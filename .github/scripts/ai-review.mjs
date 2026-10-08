// Revision automatica de Pull Requests con Gemini.
// Toma el diff del PR, lo envia a la API de Gemini y publica (o actualiza) UN solo comentario en el PR.
// Nunca bloquea el pipeline: ante cualquier error emite un aviso y termina con codigo 0.
//
// Variables de entorno:
//   GEMINI_API_KEY  (secret)  clave de Google AI Studio. Si falta, el paso se omite.
//   GEMINI_MODEL    (opcional) modelo a usar. Por defecto: gemini-3.8-flash.
//   GITHUB_TOKEN, GITHUB_REPOSITORY, PR_NUMBER, BASE_SHA, HEAD_SHA, PROJECT_CONTEXT (los provee el workflow).
import { execFileSync } from "node:child_process";

const MARKER = "<!-- omniretail-ai-review -->";
const MAX_DIFF_CHARS = 80_000;
const EXCLUDED_PATHS = [":(exclude)package-lock.json", ":(exclude)**/*.lock", ":(exclude)**/*.min.*", ":(exclude)**/*.svg"];

const {
  GEMINI_API_KEY,
  GITHUB_TOKEN,
  GITHUB_REPOSITORY,
  PR_NUMBER,
  BASE_SHA,
  HEAD_SHA,
  PROJECT_CONTEXT = "",
} = process.env;
// Una variable de GitHub vacia llega como "", por eso no basta con un valor por defecto en la desestructuracion.
const GEMINI_MODEL = process.env.GEMINI_MODEL?.trim() || "gemini-3.8-flash";

function notice(message) {
  console.log(`::notice title=Revision IA::${message}`);
}

function getDiff() {
  const diff = execFileSync("git", ["diff", "--no-color", `${BASE_SHA}...${HEAD_SHA}`, "--", ".", ...EXCLUDED_PATHS], {
    encoding: "utf8",
    maxBuffer: 64 * 1024 * 1024,
  });
  if (diff.length <= MAX_DIFF_CHARS) return { diff, truncated: false };
  return { diff: diff.slice(0, MAX_DIFF_CHARS), truncated: true };
}

function buildPrompt(diff, truncated) {
  return [
    "Eres un revisor de codigo senior. Revisa el siguiente diff de un Pull Request y responde en espanol, en Markdown.",
    PROJECT_CONTEXT && `Contexto del proyecto: ${PROJECT_CONTEXT}`,
    "Enfocate SOLO en problemas reales: bugs, errores de logica, seguridad (secretos, inyeccion, autorizacion), " +
      "manejo de errores, rendimiento evidente y pruebas faltantes para logica nueva. No comentes estilo ni formato.",
    "Formato de respuesta:",
    "1. **Resumen**: 2-3 lineas sobre que hace el PR.",
    "2. **Hallazgos**: lista con severidad (🔴 critico, 🟠 importante, 🟡 menor), archivo y explicacion breve. " +
      'Si no hay problemas relevantes, escribe "Sin hallazgos relevantes."',
    "3. **Sugerencias** (opcional): maximo 3.",
    truncated && "Nota: el diff fue truncado por tamano; indica que la revision es parcial.",
    "",
    "```diff",
    diff,
    "```",
  ]
    .filter(Boolean)
    .join("\n");
}

async function askGemini(prompt) {
  const url = `https://generativelanguage.googleapis.com/v1beta/models/${encodeURIComponent(GEMINI_MODEL)}:generateContent`;
  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json", "x-goog-api-key": GEMINI_API_KEY },
    body: JSON.stringify({
      // Sin generationConfig de muestreo: la guia de Gemini 3 pide mantener temperature en su valor por defecto.
      contents: [{ role: "user", parts: [{ text: prompt }] }],
    }),
  });
  if (!response.ok) {
    throw new Error(`Gemini respondio ${response.status}: ${(await response.text()).slice(0, 500)}`);
  }
  const data = await response.json();
  const text = data.candidates?.[0]?.content?.parts?.map((part) => part.text ?? "").join("").trim();
  if (!text) throw new Error("Gemini no devolvio contenido.");
  return text;
}

async function github(path, options = {}) {
  const response = await fetch(`https://api.github.com/repos/${GITHUB_REPOSITORY}${path}`, {
    ...options,
    headers: {
      Authorization: `Bearer ${GITHUB_TOKEN}`,
      Accept: "application/vnd.github+json",
      "X-GitHub-Api-Version": "2022-11-28",
      ...(options.body ? { "Content-Type": "application/json" } : {}),
    },
  });
  if (!response.ok) throw new Error(`GitHub API ${path} respondio ${response.status}`);
  return response.status === 204 ? null : response.json();
}

async function upsertComment(body) {
  const comments = await github(`/issues/${PR_NUMBER}/comments?per_page=100`);
  const existing = comments.find((comment) => comment.body?.includes(MARKER));
  const payload = JSON.stringify({ body });
  if (existing) {
    await github(`/issues/comments/${existing.id}`, { method: "PATCH", body: payload });
  } else {
    await github(`/issues/${PR_NUMBER}/comments`, { method: "POST", body: payload });
  }
}

async function main() {
  if (!GEMINI_API_KEY) {
    notice("GEMINI_API_KEY no esta configurado en los Secrets del repo. Se omite la revision con IA.");
    return;
  }
  if (!PR_NUMBER || !BASE_SHA || !HEAD_SHA) {
    notice("No es un Pull Request. Se omite la revision con IA.");
    return;
  }

  const { diff, truncated } = getDiff();
  if (!diff.trim()) {
    notice("El PR no tiene cambios revisables.");
    return;
  }

  const review = await askGemini(buildPrompt(diff, truncated));
  const body = [
    MARKER,
    "## 🤖 Revision automatica con IA",
    "",
    review,
    "",
    "---",
    `<sub>Generado por \`${GEMINI_MODEL}\` para el commit ${HEAD_SHA.slice(0, 7)}. ` +
      "Es una ayuda, no reemplaza la revision humana.</sub>",
  ].join("\n");

  await upsertComment(body);

  const summary = process.env.GITHUB_STEP_SUMMARY;
  if (summary) {
    const { appendFileSync } = await import("node:fs");
    appendFileSync(summary, `## 🤖 Revision automatica con IA\n\n${review}\n`);
  }
  console.log("Revision publicada en el PR.");
}

main().catch((error) => {
  console.log(`::warning title=Revision IA::${error.message}`);
});
