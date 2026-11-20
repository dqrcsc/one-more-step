import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { spawnSync } from "node:child_process";
import type { EpubBookSummary } from "@oms/shared-protocol";

export interface EpubImportOptions {
  epubPath: string;
  vaultRoot: string;
  slug?: string;
  dryRun?: boolean;
}

export interface ImportedChapterInfo {
  index: number;
  title: string;
  relPath: string;
  charCount: number;
  leadExcerpt: string;
}

export interface EpubImportResult {
  ok: boolean;
  dryRun: boolean;
  slug: string;
  title: string;
  author: string;
  publisher: string;
  language: string;
  bookDirRelPath: string;
  indexRelPath: string;
  noteRelPath: string;
  bookshelfUpdated: boolean;
  chapters: ImportedChapterInfo[];
  assetsCopied: number;
  error?: string;
}

interface ManifestItem {
  id: string;
  href: string;
  mediaType: string;
  properties: string;
}

function decodeXmlEntities(text: string): string {
  return text
    .replace(/&nbsp;/gi, " ")
    .replace(/&amp;/gi, "&")
    .replace(/&lt;/gi, "<")
    .replace(/&gt;/gi, ">")
    .replace(/&quot;/gi, '"')
    .replace(/&apos;/gi, "'")
    .replace(/&#(\d+);/g, (_, dec) => {
      const code = Number(dec);
      return Number.isFinite(code) && code > 0 ? String.fromCodePoint(code) : "";
    })
    .replace(/&#x([0-9a-f]+);/gi, (_, hex) => {
      const code = parseInt(hex, 16);
      return Number.isFinite(code) && code > 0 ? String.fromCodePoint(code) : "";
    });
}

function stripTags(html: string): string {
  return decodeXmlEntities(html.replace(/<[^>]+>/g, " "))
    .replace(/\s+/g, " ")
    .trim();
}

function parseXmlAttributes(tagContent: string): Record<string, string> {
  const attrs: Record<string, string> = {};
  const re = /([a-zA-Z0-9_:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)')/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(tagContent)) !== null) {
    attrs[m[1]] = decodeXmlEntities(m[2] ?? m[3] ?? "");
  }
  return attrs;
}

function sanitizeSlug(raw: string, fallback = "epub_book"): string {
  const ascii = raw
    .normalize("NFKD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9\u4e00-\u9fff]+/g, "_")
    .replace(/^_+|_+$/g, "")
    .slice(0, 64);
  if (ascii && /[a-z0-9]/.test(ascii)) {
    return ascii.replace(/[^\x00-\x7f]+/g, "").replace(/_+/g, "_").replace(/^_+|_+$/g, "") || fallback;
  }
  // Stable deterministic ASCII slug when book title is pure CJK
  let hash = 2166136261;
  for (let i = 0; i < raw.length; i++) {
    hash ^= raw.charCodeAt(i);
    hash = Math.imul(hash, 16777619);
  }
  return `${fallback}_${(hash >>> 0).toString(16).slice(0, 8)}`;
}

function sanitizeAssetName(raw: string): string {
  const base = path.basename(raw).replace(/[^a-zA-Z0-9._-]+/g, "_");
  return base || "image.png";
}

function yamlEscape(value: string): string {
  return JSON.stringify(String(value || "").replace(/\r?\n/g, " ").trim());
}

/**
 * Clean XHTML/HTML chapter body to structured Markdown while preserving headings,
 * blockquotes, lists, code blocks, emphasis, and rewriting local images into `assets/`.
 */
export function convertChapterXhtmlToMarkdown(
  xhtml: string,
  chapterDirAbs: string,
  onImageRef: (absImagePath: string) => string | null
): { title: string; markdown: string } {
  const titleMatch = xhtml.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  let extractedTitle = titleMatch ? stripTags(titleMatch[1]) : "";

  const bodyMatch = xhtml.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  let body = bodyMatch ? bodyMatch[1] : xhtml;

  // Strip scripts, styles, comments, nav landmarks
  body = body
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<(script|style|svg|noscript)[^>]*>[\s\S]*?<\/\1>/gi, "")
    .replace(/\r\n/g, "\n");

  // Preserve <pre><code> blocks first via placeholders
  const codeBlocks: string[] = [];
  body = body.replace(/<pre[^>]*>([\s\S]*?)<\/pre>/gi, (_, inner) => {
    const cleanCode = decodeXmlEntities(inner.replace(/<[^>]+>/g, "")).trimEnd();
    const token = `\n@@CODEBLOCK_${codeBlocks.length}@@\n`;
    codeBlocks.push(`\n\`\`\`\n${cleanCode}\n\`\`\`\n`);
    return token;
  });

  // Rewrite <img> tags to local assets Markdown
  body = body.replace(/<img\b([^>]*)\/?>/gi, (_, attrStr) => {
    const attrs = parseXmlAttributes(attrStr);
    const src = (attrs.src || "").trim();
    if (!src || src.startsWith("data:") || /^https?:\/\//i.test(src)) return "";
    const decodedSrc = decodeURIComponent(src.split("#")[0].split("?")[0]);
    const absImg = path.resolve(chapterDirAbs, decodedSrc);
    const assetRel = onImageRef(absImg);
    if (!assetRel) return "";
    const alt = (attrs.alt || "插图").replace(/[\[\]]/g, "").trim() || "插图";
    return `\n\n![${alt}](${assetRel})\n\n`;
  });

  // Headings h1..h6
  body = body.replace(/<h([1-6])[^>]*>([\s\S]*?)<\/h\1>/gi, (_, levelStr, inner) => {
    const level = Math.min(6, Math.max(1, Number(levelStr) || 2));
    const text = stripTags(inner);
    if (!text) return "\n";
    if (!extractedTitle && level <= 2) extractedTitle = text;
    return `\n\n${"#".repeat(level)} ${text}\n\n`;
  });

  // Blockquotes
  body = body.replace(/<blockquote[^>]*>([\s\S]*?)<\/blockquote>/gi, (_, inner) => {
    const text = stripTags(inner);
    if (!text) return "\n";
    return `\n\n> ${text}\n\n`;
  });

  // List items
  body = body.replace(/<li[^>]*>([\s\S]*?)<\/li>/gi, (_, inner) => {
    const text = stripTags(inner);
    return text ? `\n- ${text}` : "";
  });

  // Inline formatting
  body = body
    .replace(/<(strong|b)[^>]*>([\s\S]*?)<\/\1>/gi, (_, __, inner) => {
      const t = stripTags(inner);
      return t ? `**${t}**` : "";
    })
    .replace(/<(em|i)[^>]*>([\s\S]*?)<\/\1>/gi, (_, __, inner) => {
      const t = stripTags(inner);
      return t ? `*${t}*` : "";
    })
    .replace(/<code[^>]*>([\s\S]*?)<\/code>/gi, (_, inner) => {
      const t = stripTags(inner);
      return t ? `\`${t}\`` : "";
    })
    .replace(/<br\s*\/?>/gi, "\n")
    .replace(/<hr\s*\/?>/gi, "\n\n---\n\n")
    .replace(/<\/(p|div|section|article|tr|ul|ol|figure|figcaption)>/gi, "\n\n")
    .replace(/<t[dh][^>]*>([\s\S]*?)<\/t[dh]>/gi, (_, inner) => `${stripTags(inner)} | `);

  // Strip remaining tags & normalize whitespace
  body = decodeXmlEntities(body.replace(/<[^>]+>/g, ""));
  body = body
    .split("\n")
    .map((line) => line.replace(/[ \t]+/g, " ").trimEnd())
    .join("\n")
    .replace(/\n{3,}/g, "\n\n")
    .trim();

  // Restore code blocks
  body = body.replace(/@@CODEBLOCK_(\d+)@@/g, (_, idxStr) => codeBlocks[Number(idxStr)] || "");

  return {
    title: extractedTitle.trim(),
    markdown: body.trim(),
  };
}

function parseTocTitles(extractRoot: string, opfDir: string, manifest: Map<string, ManifestItem>): Map<string, string> {
  const titleByRelHref = new Map<string, string>();
  const register = (baseDir: string, rawHref: string, label: string) => {
    const cleanLabel = label.replace(/\s+/g, " ").trim();
    if (!rawHref || !cleanLabel) return;
    const cleanHref = decodeURIComponent(rawHref.split("#")[0]);
    const absTarget = path.resolve(baseDir, cleanHref);
    const relFromRoot = path.relative(extractRoot, absTarget).replace(/\\/g, "/");
    if (!titleByRelHref.has(relFromRoot)) {
      titleByRelHref.set(relFromRoot, cleanLabel);
    }
  };

  // 1. EPUB3 nav document (`properties="nav"`)
  for (const item of manifest.values()) {
    if (item.properties.split(/\s+/).includes("nav")) {
      const navAbs = path.resolve(opfDir, decodeURIComponent(item.href));
      if (fs.existsSync(navAbs)) {
        const navXml = fs.readFileSync(navAbs, "utf8");
        const navDir = path.dirname(navAbs);
        const linkRe = /<a\b([^>]*)>([\s\S]*?)<\/a>/gi;
        let m: RegExpExecArray | null;
        while ((m = linkRe.exec(navXml)) !== null) {
          const attrs = parseXmlAttributes(m[1]);
          if (attrs.href) register(navDir, attrs.href, stripTags(m[2]));
        }
      }
    }
  }

  // 2. EPUB2 toc.ncx fallback
  for (const item of manifest.values()) {
    if (item.mediaType.includes("dtbncx") || item.href.toLowerCase().endsWith(".ncx")) {
      const ncxAbs = path.resolve(opfDir, decodeURIComponent(item.href));
      if (fs.existsSync(ncxAbs)) {
        const ncxXml = fs.readFileSync(ncxAbs, "utf8");
        const ncxDir = path.dirname(ncxAbs);
        const pointRe = /<navPoint\b[\s\S]*?<navLabel[^>]*>[\s\S]*?<text[^>]*>([\s\S]*?)<\/text>[\s\S]*?<\/navLabel>[\s\S]*?<content\b([^>]*)\/?>/gi;
        let m: RegExpExecArray | null;
        while ((m = pointRe.exec(ncxXml)) !== null) {
          const attrs = parseXmlAttributes(m[2]);
          if (attrs.src) register(ncxDir, attrs.src, stripTags(m[1]));
        }
      }
    }
  }

  return titleByRelHref;
}

/**
 * Imports a local `.epub` ebook into the user's Obsidian / personal knowledge base
 * following `01_inputs/books/instruction.md`:
 * - Chapterized Markdown under `01_inputs/books/sources/<slug>/`
 * - Extracted illustrations under `01_inputs/books/sources/<slug>/assets/`
 * - Book index `01_inputs/books/sources/<slug>/index.md`
 * - Non-destructive companion reading note scaffold `01_inputs/books/notes/<slug>_note.md`
 * - Idempotent registration in `01_inputs/books/bookshelf.md`
 */
export function importEpubToVault(opts: EpubImportOptions): EpubImportResult {
  const epubAbs = path.resolve(opts.epubPath);
  const vaultRoot = path.resolve(opts.vaultRoot);
  const dryRun = Boolean(opts.dryRun);

  if (!fs.existsSync(epubAbs) || !fs.statSync(epubAbs).isFile()) {
    return {
      ok: false,
      dryRun,
      slug: "",
      title: "",
      author: "",
      publisher: "",
      language: "",
      bookDirRelPath: "",
      indexRelPath: "",
      noteRelPath: "",
      bookshelfUpdated: false,
      chapters: [],
      assetsCopied: 0,
      error: `EPUB file not found: ${epubAbs}`,
    };
  }

  const tmpDir = fs.mkdtempSync(path.join(os.tmpdir(), "oms-epub-"));
  try {
    const unzip = spawnSync("unzip", ["-oq", epubAbs, "-d", tmpDir], {
      encoding: "utf8",
      timeout: 30_000,
    });
    if (unzip.status !== 0) {
      throw new Error(`unzip failed (${unzip.status}): ${(unzip.stderr || "").trim() || "invalid EPUB archive"}`);
    }

    // 1. Locate OPF via META-INF/container.xml
    const containerPath = path.join(tmpDir, "META-INF", "container.xml");
    if (!fs.existsSync(containerPath)) {
      throw new Error("Invalid EPUB: missing META-INF/container.xml");
    }
    const containerXml = fs.readFileSync(containerPath, "utf8");
    const rootfileMatch = containerXml.match(/<rootfile\b([^>]*)\/?>/i);
    const opfRelPath = rootfileMatch ? parseXmlAttributes(rootfileMatch[1])["full-path"] : "";
    if (!opfRelPath) {
      throw new Error("Invalid EPUB: container.xml missing rootfile full-path");
    }
    const opfAbs = path.resolve(tmpDir, opfRelPath);
    if (!opfAbs.startsWith(tmpDir) || !fs.existsSync(opfAbs)) {
      throw new Error(`Invalid EPUB: OPF not found at ${opfRelPath}`);
    }
    const opfDir = path.dirname(opfAbs);
    const opfXml = fs.readFileSync(opfAbs, "utf8");

    const readDcTag = (tag: string): string => {
      const m = opfXml.match(new RegExp(`<(?:dc:)?${tag}\\b[^>]*>([\\s\\S]*?)<\\/(?:dc:)?${tag}>`, "i"));
      return m ? stripTags(m[1]) : "";
    };

    const bookTitle = readDcTag("title") || path.basename(epubAbs, path.extname(epubAbs));
    const bookAuthor = readDcTag("creator") || "未知作者";
    const bookPublisher = readDcTag("publisher") || "";
    const bookLanguage = readDcTag("language") || "zh";
    const bookDescription = readDcTag("description") || "";

    const slug = opts.slug
      ? sanitizeSlug(opts.slug, "book")
      : sanitizeSlug(path.basename(epubAbs, path.extname(epubAbs)), sanitizeSlug(bookTitle, "book"));

    // 2. Parse manifest & spine
    const manifest = new Map<string, ManifestItem>();
    const itemRe = /<item\b([^>]*)\/?>/gi;
    let m: RegExpExecArray | null;
    while ((m = itemRe.exec(opfXml)) !== null) {
      const a = parseXmlAttributes(m[1]);
      if (a.id && a.href) {
        manifest.set(a.id, {
          id: a.id,
          href: a.href,
          mediaType: a["media-type"] || "",
          properties: a.properties || "",
        });
      }
    }

    const spineIds: string[] = [];
    const spineMatch = opfXml.match(/<spine\b[^>]*>([\s\S]*?)<\/spine>/i);
    if (spineMatch) {
      const itemrefRe = /<itemref\b([^>]*)\/?>/gi;
      while ((m = itemrefRe.exec(spineMatch[1])) !== null) {
        const a = parseXmlAttributes(m[1]);
        if (a.idref) spineIds.push(a.idref);
      }
    }

    const tocTitles = parseTocTitles(tmpDir, opfDir, manifest);
    const todayIso = new Date().toISOString().slice(0, 10);

    const booksDirAbs = path.join(vaultRoot, "01_inputs", "books");
    const bookDirRelPath = path.posix.join("01_inputs", "books", "sources", slug);
    const bookDirAbs = path.join(vaultRoot, bookDirRelPath);
    const assetsDirAbs = path.join(bookDirAbs, "assets");
    const notesDirAbs = path.join(booksDirAbs, "notes");
    const indexRelPath = path.posix.join(bookDirRelPath, "index.md");
    const noteRelPath = path.posix.join("01_inputs", "books", "notes", `${slug}_note.md`);

    if (!dryRun) {
      fs.mkdirSync(bookDirAbs, { recursive: true });
      fs.mkdirSync(notesDirAbs, { recursive: true });
    }

    const copiedAssets = new Map<string, string>();
    const chapters: ImportedChapterInfo[] = [];

    let chapterNum = 0;
    for (const idref of spineIds) {
      const item = manifest.get(idref);
      if (!item) continue;
      if (!/(xhtml|html|xml)/i.test(item.mediaType) && !/\.(x?html?|xml)$/i.test(item.href)) {
        continue;
      }
      const chAbs = path.resolve(opfDir, decodeURIComponent(item.href));
      if (!chAbs.startsWith(tmpDir) || !fs.existsSync(chAbs)) continue;

      const relFromExtract = path.relative(tmpDir, chAbs).replace(/\\/g, "/");
      const rawHtml = fs.readFileSync(chAbs, "utf8");
      const chDirAbs = path.dirname(chAbs);

      const converted = convertChapterXhtmlToMarkdown(rawHtml, chDirAbs, (absImgPath) => {
        if (!absImgPath.startsWith(tmpDir) || !fs.existsSync(absImgPath)) return null;
        const existing = copiedAssets.get(absImgPath);
        if (existing) return existing;
        const baseName = sanitizeAssetName(absImgPath);
        let destName = baseName;
        let counter = 2;
        const usedNames = new Set(copiedAssets.values());
        while (usedNames.has(`assets/${destName}`)) {
          const ext = path.extname(baseName);
          const stem = path.basename(baseName, ext);
          destName = `${stem}_${counter}${ext}`;
          counter++;
        }
        const relAsset = `assets/${destName}`;
        copiedAssets.set(absImgPath, relAsset);
        if (!dryRun) {
          fs.mkdirSync(assetsDirAbs, { recursive: true });
          fs.copyFileSync(absImgPath, path.join(assetsDirAbs, destName));
        }
        return relAsset;
      });

      // Skip empty non-content spine pages (e.g., blank separator pages without text or images)
      if (converted.markdown.replace(/\s+/g, "").length < 12 && !converted.markdown.includes("![")) {
        continue;
      }

      chapterNum += 1;
      const tocTitle = tocTitles.get(relFromExtract) || "";
      const chTitle = tocTitle || converted.title || `第 ${chapterNum} 章`;
      const padded = String(chapterNum).padStart(2, "0");
      const chSlug = sanitizeSlug(chTitle, `section_${padded}`).slice(0, 36);
      const chFileName = `ch${padded}_${chSlug}.md`;
      const chRelPath = path.posix.join(bookDirRelPath, chFileName);

      const plainLead = converted.markdown
        .replace(/!\[[^\]]*\]\([^)]+\)/g, " ")
        .replace(/^#+\s+.*$/gm, " ")
        .replace(/\s+/g, " ")
        .trim()
        .slice(0, 140);

      const chapterDoc = [
        "---",
        "type: book_chapter",
        `book: ${yamlEscape(bookTitle)}`,
        `book_slug: ${yamlEscape(slug)}`,
        `author: ${yamlEscape(bookAuthor)}`,
        `chapter_index: ${chapterNum}`,
        `chapter_title: ${yamlEscape(chTitle)}`,
        `captured_date: ${todayIso}`,
        "process_level: raw",
        "triage: process_later",
        "tags:",
        "  - book",
        "  - epub",
        `  - book_${slug}`,
        "---",
        "",
        `# ${chTitle}`,
        "",
        `- **所属书籍**：[[index|${bookTitle}]]`,
        `- **作者**：${bookAuthor}`,
        `- **章节序号**：第 ${chapterNum} 章`,
        "",
        "---",
        "",
        converted.markdown,
        "",
      ].join("\n");

      if (!dryRun) {
        fs.writeFileSync(path.join(bookDirAbs, chFileName), chapterDoc, "utf8");
      }

      chapters.push({
        index: chapterNum,
        title: chTitle,
        relPath: chRelPath,
        charCount: converted.markdown.length,
        leadExcerpt: plainLead,
      });
    }

    // 3. Write book index.md
    const totalChars = chapters.reduce((acc, c) => acc + c.charCount, 0);
    const tocLines = chapters.map((c) => {
      const fileStem = path.basename(c.relPath, ".md");
      return `${c.index}. [[${fileStem}|${c.title}]]（约 ${c.charCount} 字）${c.leadExcerpt ? ` — ${c.leadExcerpt}` : ""}`;
    });

    const indexDoc = [
      "---",
      "type: book_source_index",
      `title: ${yamlEscape(bookTitle)}`,
      `book_slug: ${yamlEscape(slug)}`,
      `author: ${yamlEscape(bookAuthor)}`,
      ...(bookPublisher ? [`publisher: ${yamlEscape(bookPublisher)}`] : []),
      `language: ${yamlEscape(bookLanguage)}`,
      `chapter_count: ${chapters.length}`,
      `total_chars: ${totalChars}`,
      `source_format: epub`,
      `source_file: ${yamlEscape(path.basename(epubAbs))}`,
      `captured_date: ${todayIso}`,
      "process_level: raw",
      "triage: process_later",
      "tags:",
      "  - book",
      "  - epub",
      "---",
      "",
      `# ${bookTitle}`,
      "",
      `- **作者**：${bookAuthor}`,
      ...(bookPublisher ? [`- **出版社**：${bookPublisher}`] : []),
      `- **章节数**：${chapters.length} 章（总计约 ${totalChars} 字）`,
      `- **伴读整理**：[[${slug}_note|${bookTitle} · 阅读整理]]`,
      `- **入库日期**：${todayIso}`,
      ...(bookDescription ? ["", "## 简介", "", bookDescription] : []),
      "",
      "## 章节目录",
      "",
      ...tocLines,
      "",
    ].join("\n");

    if (!dryRun) {
      fs.writeFileSync(path.join(vaultRoot, indexRelPath), indexDoc, "utf8");
    }

    // 4. Create companion reading note scaffold ONLY if it doesn't already exist (preserve user edits!)
    const noteAbs = path.join(vaultRoot, noteRelPath);
    if (!dryRun && !fs.existsSync(noteAbs)) {
      const noteDoc = [
        "---",
        "type: source_note",
        "source_type: book",
        `title: ${yamlEscape(`${bookTitle} · 阅读整理`)}`,
        `book: ${yamlEscape(bookTitle)}`,
        `author: ${yamlEscape(bookAuthor)}`,
        `source_path: ${yamlEscape(indexRelPath)}`,
        `created: ${todayIso}`,
        `updated: ${todayIso}`,
        "process_level: light",
        "triage: process_later",
        "tags:",
        "  - book_note",
        "  - epub",
        "---",
        "",
        `# ${bookTitle} · 阅读整理`,
        "",
        `- **原书目录**：[[sources/${slug}/index|${bookTitle}]]（共 ${chapters.length} 章，约 ${totalChars} 字）`,
        `- **作者**：${bookAuthor}`,
        "",
        "## 核心命题与框架",
        "",
        bookDescription || "（待阅读或触发 AI 章节精读后补充）",
        "",
        "## 章节导航",
        "",
        ...chapters.slice(0, 40).map((c) => `- 第 ${c.index} 章：[[${path.basename(c.relPath, ".md")}|${c.title}]]`),
        "",
        "## 个人思考与主题关联",
        "",
        "- ",
        "",
      ].join("\n");
      fs.writeFileSync(noteAbs, noteDoc, "utf8");
    }

    // 5. Idempotently register in 01_inputs/books/bookshelf.md
    let bookshelfUpdated = false;
    const bookshelfAbs = path.join(booksDirAbs, "bookshelf.md");
    if (!dryRun && fs.existsSync(bookshelfAbs)) {
      const existingShelf = fs.readFileSync(bookshelfAbs, "utf8");
      const marker = `sources/${slug}/index.md`;
      if (!existingShelf.includes(marker)) {
        const safeTitle = bookTitle.replace(/\|/g, "｜");
        const safeAuthor = bookAuthor.replace(/\|/g, "｜");
        const newRow = `| ${safeTitle} | ${safeAuthor} | EPUB 已拆章入库（${chapters.length} 章） | ${todayIso} | [目录](${marker}) | [阅读整理](notes/${slug}_note.md) |`;
        if (existingShelf.includes("## reading")) {
          // Insert right after the table header separator under ## reading
          const updated = existingShelf.replace(
            /(## reading[\s\S]*?\| --- \| --- \| --- \| --- \| --- \| --- \|[^\n]*\n)/,
            `$1${newRow}\n`
          );
          if (updated !== existingShelf) {
            fs.writeFileSync(bookshelfAbs, updated, "utf8");
            bookshelfUpdated = true;
          }
        }
        if (!bookshelfUpdated) {
          fs.appendFileSync(bookshelfAbs, `\n${newRow}\n`, "utf8");
          bookshelfUpdated = true;
        }
      }
    }

    return {
      ok: true,
      dryRun,
      slug,
      title: bookTitle,
      author: bookAuthor,
      publisher: bookPublisher,
      language: bookLanguage,
      bookDirRelPath,
      indexRelPath,
      noteRelPath,
      bookshelfUpdated,
      chapters,
      assetsCopied: copiedAssets.size,
    };
  } catch (err: any) {
    return {
      ok: false,
      dryRun,
      slug: "",
      title: "",
      author: "",
      publisher: "",
      language: "",
      bookDirRelPath: "",
      indexRelPath: "",
      noteRelPath: "",
      bookshelfUpdated: false,
      chapters: [],
      assetsCopied: 0,
      error: err?.message || String(err),
    };
  } finally {
    try {
      fs.rmSync(tmpDir, { recursive: true, force: true });
    } catch {}
  }
}

export function listImportedBooks(vaultRoot: string): EpubBookSummary[] {
  const sourcesDir = path.join(path.resolve(vaultRoot), "01_inputs", "books", "sources");
  if (!fs.existsSync(sourcesDir)) return [];
  const results: EpubBookSummary[] = [];
  let entries: fs.Dirent[] = [];
  try {
    entries = fs.readdirSync(sourcesDir, { withFileTypes: true });
  } catch {
    return [];
  }
  for (const e of entries) {
    if (!e.isDirectory() || e.name.startsWith(".")) continue;
    const slug = e.name;
    const indexAbs = path.join(sourcesDir, slug, "index.md");
    if (!fs.existsSync(indexAbs)) continue;
    try {
      const st = fs.statSync(indexAbs);
      const raw = fs.readFileSync(indexAbs, "utf8").slice(0, 4096);
      const titleM = raw.match(/^title:\s*"?([^"\n]+)"?/m);
      const authorM = raw.match(/^author:\s*"?([^"\n]+)"?/m);
      const chM = raw.match(/^chapter_count:\s*(\d+)/m);
      const noteRel = path.posix.join("01_inputs", "books", "notes", `${slug}_note.md`);
      results.push({
        slug,
        title: titleM?.[1]?.trim() || slug,
        author: authorM?.[1]?.trim() || "未知作者",
        chapterCount: Number(chM?.[1] || 0),
        indexRelPath: path.posix.join("01_inputs", "books", "sources", slug, "index.md"),
        noteRelPath: fs.existsSync(path.join(vaultRoot, noteRel)) ? noteRel : undefined,
        updatedAtIso: st.mtime.toISOString(),
      });
    } catch {}
  }
  return results.sort((a, b) => Date.parse(b.updatedAtIso) - Date.parse(a.updatedAtIso));
}
