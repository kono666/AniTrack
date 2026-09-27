/**
 * [可选工具] 从 AniList 公开 GraphQL API 拉取 TOP 日漫数据，生成 SQL 插入脚本
 * 用法: node fetch-anime.js
 *
 * 注意: 应用正常运行时由 CachePreloader 自动从 Bangumi 拉取数据并建缓存，
 *       无需执行本脚本。本脚本仅用于离线批量生成种子数据，且会先清空
 *       本地 anime / episode 两张表再重新写入。
 *
 * 输出: backend/src/main/resources/data.sql
 *       (Spring Boot 启动时会自动执行该文件, 故执行本脚本后需重启后端)
 */

const fs = require('fs');
const path = require('path');

const ANILIST_API = 'https://graphql.anilist.co';
const TOTAL = 200;
const PER_PAGE = 50;

// 不要同时在 GraphQL 层过滤 format 和 countryOfOrigin，分开过滤
const QUERY = `
query ($page: Int, $perPage: Int) {
  Page(page: $page, perPage: $perPage) {
    media(type: ANIME, sort: POPULARITY_DESC) {
      id
      title { romaji english native }
      episodes
      format
      countryOfOrigin
      description
      coverImage { large }
      startDate { year month day }
      averageScore
      genres
      status
    }
  }
}`;

async function fetchPage(page) {
  const res = await fetch(ANILIST_API, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'Accept': 'application/json' },
    body: JSON.stringify({ query: QUERY, variables: { page, perPage: PER_PAGE } })
  });
  const json = await res.json();
  if (json.errors) {
    console.error('  API Error:', JSON.stringify(json.errors));
    return [];
  }
  return json.data.Page.media;
}

function escapeSQL(str) {
  if (!str) return '';
  return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/\n/g, ' ').replace(/\r/g, '');
}

function formatDate(year, month, day) {
  if (!year) return null;
  const m = month ? String(month).padStart(2, '0') : '01';
  return `${year}-${m}-${String(day || 1).padStart(2, '0')}`;
}

function formatSummary(desc) {
  if (!desc) return '';
  let text = desc.replace(/<[^>]+>/g, '').replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"');
  if (text.length > 500) text = text.substring(0, 497) + '...';
  return escapeSQL(text);
}

async function main() {
  console.log('从 AniList 拉取日漫数据...\n');

  let allAnime = [];
  const pages = Math.ceil(500 / PER_PAGE); // 拉多点再筛选

  for (let p = 1; p <= pages; p++) {
    console.log(`第 ${p}/${pages} 页...`);
    try {
      const batch = await fetchPage(p);
      // 只保留日本的 TV 动画，有剧集数
      const filtered = batch.filter(a =>
        a.countryOfOrigin === 'JP' &&
        (a.format === 'TV' || a.format === 'TV_SHORT') &&
        a.episodes != null && a.episodes > 0
      );
      console.log(`  获取 ${batch.length} 条，日漫TV ${filtered.length} 条`);
      allAnime.push(...filtered);
      if (allAnime.length >= TOTAL) break;
    } catch (e) {
      console.error(`  失败: ${e.message}`);
    }
    if (p < pages) await new Promise(r => setTimeout(r, 800));
  }

  allAnime = allAnime.slice(0, TOTAL);
  console.log(`\n共筛选出 ${allAnime.length} 部日漫\n`);

  let sql = '-- 番剧种子数据 (来自 AniList)\n';
  sql += `-- ${allAnime.length} 部日漫\n\n`;
  sql += 'DELETE FROM episode;\nDELETE FROM anime;\n';
  sql += 'ALTER TABLE anime AUTO_INCREMENT = 1;\n';
  sql += 'ALTER TABLE episode AUTO_INCREMENT = 1;\n\n';

  for (let i = 0; i < allAnime.length; i++) {
    const a = allAnime[i];
    const title = escapeSQL(a.title.romaji || a.title.english || 'Unknown');
    const titleCn = escapeSQL(a.title.native || a.title.romaji || '');
    const cover = escapeSQL(a.coverImage?.large || '');
    const date = formatDate(a.startDate?.year, a.startDate?.month, a.startDate?.day);
    const summary = formatSummary(a.description);
    const eps = a.episodes;
    const rating = a.averageScore ? (a.averageScore / 10).toFixed(1) : null;
    const rc = a.averageScore ? Math.floor(a.averageScore * 100) + 500 : 500;
    const genres = (a.genres || []).slice(0, 8).join(',');
    const status = a.status === 'FINISHED' ? 'finished' : 'airing';
    const season = date ? date.substring(0, 7) : '2023-01';

    sql += `INSERT INTO anime (id,title,title_cn,summary,cover_url,date,total_episodes,rating,rating_count,tags,season,\`rank\`,status) VALUES(`;
    sql += `${a.id},'${title}','${titleCn}','${summary}','${cover}',`;
    sql += date ? `'${date}'` : 'NULL';
    sql += `,${eps},${rating || 'NULL'},${rc},'${genres}','${season}',${i + 1},'${status}');\n`;
  }

  // 剧集
  sql += '\n-- 剧集\n';
  let epId = 1;
  for (const a of allAnime) {
    const eps = Math.min(a.episodes || 12, 60);
    for (let e = 1; e <= eps; e++) {
      sql += `INSERT INTO episode (id,anime_id,episode_num,title,duration) VALUES(${epId},${a.id},${e},'第${e}集','24m');\n`;
      epId++;
    }
  }

  const outPath = path.join(__dirname, '..', 'backend', 'src', 'main', 'resources', 'data.sql');
  fs.writeFileSync(outPath, sql, 'utf-8');
  console.log(`✅ 完成: ${allAnime.length} 部番剧, ${epId - 1} 集`);
  console.log(`   输出: ${outPath}`);
}

main().catch(e => { console.error('失败:', e.message); process.exit(1); });
