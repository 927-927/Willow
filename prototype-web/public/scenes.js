// Renders a mock "video" for each sample: an animated CSS scene plus the WeChat-Channels-style
// chrome (side buttons, author, caption, product link).

export const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
}[c]));

const CHESS_PIECES = ['炮', '马', '车', '帅', '将', '相'];

function sceneDecor(style, emoji) {
  switch (style) {
    case 'alarm':
      return `<div class="decor-flash"></div><div class="decor-arrows">⚠️ ⚠️ ⚠️</div><div class="scene-emoji pulse">${emoji}</div>`;
    case 'water':
      return `<div class="decor-waves"><i></i><i></i><i></i></div><div class="scene-emoji bob">${emoji}</div>`;
    case 'garden':
      return `<div class="decor-soil">${'<i>🌱</i>'.repeat(6)}</div><div class="scene-emoji grow">${emoji}</div>`;
    case 'chess':
      return `<div class="decor-board">${CHESS_PIECES.map((p, i) => `<i class="${i % 2 ? 'black' : 'red'}">${p}</i>`).join('')}</div><div class="scene-emoji">${emoji}</div>`;
    case 'clinic':
      return `<div class="decor-cross">✚</div><div class="scene-emoji">${emoji}</div>`;
    case 'kitchen':
      return `<div class="decor-steam"><i></i><i></i><i></i></div><div class="scene-emoji">${emoji}</div>`;
    case 'craft':
      return `<div class="scene-emoji spin">${emoji}</div>`;
    case 'sunset':
      return `<div class="decor-sun"></div><div class="decor-lake"></div>`;
    case 'warm':
    default:
      return `<div class="decor-hearts"><i>❤</i><i>❤</i><i>❤</i></div><div class="scene-emoji bob">${emoji}</div>`;
  }
}

function verifiedBadge(verified) {
  if (verified === 'medical') return '<span class="badge-v medical">医生认证</span>';
  if (verified === 'creator') return '<span class="badge-v">✓</span>';
  return '';
}

export function renderVideo(video) {
  const el = document.createElement('article');
  el.className = `video scene-${video.scene.style}`;
  el.dataset.id = video.id;
  el.innerHTML = `
    <div class="scene">
      ${sceneDecor(video.scene.style, esc(video.scene.emoji))}
      <div class="scene-text">${esc(video.onScreenText)}</div>
    </div>
    <div class="side">
      <div class="avatar">${esc([...video.author.name][0])}</div>
      <div class="side-btn">♥<small>${esc(video.likes)}</small></div>
      <div class="side-btn">💬<small>评论</small></div>
      <div class="side-btn">⭐<small>收藏</small></div>
      <div class="side-btn">↗<small>分享</small></div>
    </div>
    <div class="info">
      <div class="author">@${esc(video.author.name)} ${verifiedBadge(video.author.verified)}</div>
      <p class="caption">${esc(video.caption)}</p>
      <p class="hashtags">${video.hashtags.map(esc).join(' ')}</p>
      ${video.product ? `<button type="button" class="product" data-action="product">🛒 ${esc(video.product.title)} <b>¥${video.product.price}</b></button>` : ''}
    </div>
    <div class="progress"><i></i></div>
    <div class="muted-icon" hidden>🔇</div>`;
  return el;
}
