(function () {
  'use strict';
  if (window.__onePageTools) return;
  var refs = [], MAX_REFS = 320, MAX_ITEMS = 200;
  function text(e) { return String(e.getAttribute('aria-label') || e.getAttribute('title') || e.textContent || '').replace(/\s+/g, ' ').trim(); }
  function ref(e) { var i = refs.indexOf(e); if (i >= 0) return i; if (refs.length >= MAX_REFS) return -1; refs.push(e); return refs.length - 1; }
  function connected(e) { return e && e.ownerDocument && e.ownerDocument.documentElement.contains(e); }
  function visible(e) { var s = e.ownerDocument.defaultView.getComputedStyle(e); return s.display !== 'none' && s.visibility !== 'hidden'; }
  function documents() {
    var result = [];
    function visit(d, depth) {
      if (!d || depth > 3 || result.length >= 12) return;
      result.push(d);
      var frames = d.querySelectorAll('iframe');
      for (var i = 0; i < Math.min(frames.length, 24); i++) { try { visit(frames[i].contentDocument, depth + 1); } catch (_) {} }
    }
    visit(document, 0); return result;
  }
  function kind(label, e) {
    if (/^(?:CCTV\s*[-－]?\s*\d{1,2}(?:\+|PLUS)?(?:\s*[-－]?\s*[^\s]{0,10})?|CGTN(?:\s*[^\s]{0,12})?|.{2,8}(?:卫视|频道))$/i.test(label)) return 'channel';
    if (/^(?:第\s*\d{1,4}\s*[集期话]|(?:EP|Episode)\s*\d{1,4}|\d{1,4}\s*[集期话])(?:\s*[^\d]{0,8})?$/i.test(label)) return 'episode';
    if (/^\d{1,3}$/.test(label) && Number(label) > 0) {
      var p = e.parentElement;
      for (var n = 0; p && n < 5; n++, p = p.parentElement) {
        if (/episode|anthology|playlist|series|season|drama|选集|剧集/i.test(String(p.className) + ' ' + p.id)) return 'episode';
      }
    }
    return '';
  }
  function container(e) {
    var p = e.parentElement, fallback = p;
    for (var i = 0; p && i < 6; i++, p = p.parentElement) {
      var c = String(p.className || '') + ' ' + p.id;
      if (p.tagName === 'UL' || p.tagName === 'OL' || p.getAttribute('role') === 'list' ||
          (/list|episode|anthology|channel|playlist|series/i.test(c) && !/\bitem\b|[-_]item(?:\s|$)/i.test(c))) return p;
    }
    return fallback;
  }
  function current(e) {
    for (var n = 0; e && n < 3; n++, e = e.parentElement) {
      if (/^(true|page|step)$/.test(e.getAttribute('aria-current') || '') || e.getAttribute('aria-selected') === 'true' ||
          /(?:^|[\s_-])(?:active|selected|current|playing)(?:$|[\s_-])/i.test(String(e.className))) return true;
    }
    return false;
  }
  function scan() {
    refs = [];
    var videos = [], groups = [], groupNodes = [], seen = {}, count = 0;
    documents().forEach(function (d) {
      var list = d.querySelectorAll('video');
      for (var i = 0; i < Math.min(list.length, 24); i++) {
        var v = list[i], r = v.getBoundingClientRect();
        if (!visible(v) || (r.width < 4 && !v.videoWidth)) continue;
        var id = ref(v); if (id < 0) continue;
        var title = v.getAttribute('title') || v.getAttribute('aria-label') || '';
        videos.push({id: id, label: title || '视频 ' + (videos.length + 1), width: v.videoWidth || Math.round(r.width), height: v.videoHeight || Math.round(r.height), playing: !v.paused && !v.ended && !v.error, selected: window.__oneSelectedVideo === v});
      }
      var candidates = d.querySelectorAll('a,button,[role="button"],li,[onclick],[class*="channel"],[class*="episode"],[class*="anthology"],[class*="tv-main-con-r"]');
      for (var j = 0; j < Math.min(candidates.length, 2500) && count < MAX_ITEMS; j++) {
        var e = candidates[j], label = text(e);
        if (!label || label.length > 36 || e.disabled || e.getAttribute('aria-disabled') === 'true') continue;
        var type = kind(label, e); if (!type) continue;
        if (!visible(e)) continue;
        var href = e.tagName === 'A' ? e.href : '';
        if (href && !/^https?:/i.test(href) && href.indexOf('javascript:') !== 0) continue;
        var parent = container(e); if (!parent) continue;
        var gi = -1;
        for (var g = 0; g < groupNodes.length; g++) if (groupNodes[g] === parent && groups[g].type === type) { gi = g; break; }
        if (gi < 0) { if (groups.length >= 12) continue; gi = groups.length; groupNodes.push(parent); groups.push({type: type, label: type === 'channel' ? '频道' : '选集', items: []}); }
        var key = gi + '|' + type + '|' + label;
        if (seen[key]) continue;
        var eid = ref(e); if (eid < 0) continue;
        seen[key] = true; groups[gi].items.push({id: eid, label: label, selected: current(e)}); count++;
      }
    });
    groups = groups.filter(function (g) { return g.items.length >= 2; });
    return JSON.stringify({videos: videos, groups: groups, truncated: count >= MAX_ITEMS});
  }
  window.__onePageTools = {
    scan: scan,
    probeVideos: function () {
      var count = 0, playing = 0;
      documents().forEach(function (d) { var all = d.querySelectorAll('video'); for (var i = 0; i < Math.min(all.length, 24); i++) {
        if (!visible(all[i]) || all[i].getBoundingClientRect().width < 4) continue;
        count++; if (!all[i].paused && !all[i].ended && !all[i].error && all[i].readyState >= 2) playing++;
      }});
      return JSON.stringify({count: count, playing: playing});
    },
    clear: function () { refs = []; if (window.__oneSelectedVideo && !connected(window.__oneSelectedVideo)) delete window.__oneSelectedVideo; },
    selectVideo: function (id) {
      var v = refs[id]; if (!connected(v) || v.tagName !== 'VIDEO') return false;
      documents().forEach(function (d) { var all = d.querySelectorAll('video'); for (var i = 0; i < all.length; i++) if (all[i] !== v) all[i].pause(); });
      window.__oneSelectedVideo = v; refs = []; return true;
    },
    activate: function (id, label) {
      var e = refs[id]; if (!connected(e) || text(e) !== label || !visible(e)) return false;
      refs = []; delete window.__oneSelectedVideo; e.click(); return true;
    }
  };
})();
