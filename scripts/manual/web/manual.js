(function () {
  "use strict";

  var root = document.body.dataset.root || "";
  var current = document.body.dataset.page || "";
  var isHome = document.body.classList.contains("home");

  function el(tag, className, text) {
    var node = document.createElement(tag);
    if (className) node.className = className;
    if (text != null) node.textContent = text;
    return node;
  }

  function renderTree(nodes, parent) {
    (nodes || []).forEach(function (node) {
      if (node.p) {
        var item = el("div", "nav-item");
        var link = el("a", null, node.t || node.p);
        link.href = root + node.p;
        if (node.p === current) link.className = "active";
        item.appendChild(link);
        parent.appendChild(item);
      }
      if (node.c && node.c.length) {
        var group = el("div", "nav-subgroup");
        if (node.t) group.appendChild(el("div", "nav-subgroup-title", node.t));
        renderTree(node.c, group);
        parent.appendChild(group);
      }
    });
  }

  var sidebar = document.getElementById("sidebar");
  var sidebarNav = document.getElementById("sidebar-nav");
  if (sidebarNav && window.MANUAL_NAV) {
    window.MANUAL_NAV.forEach(function (section) {
      var box = el("div", "nav-section");
      box.appendChild(el("div", "nav-section-title", section.t));
      (section.c || []).forEach(function (group) {
        var groupBox = el("div", "nav-group");
        if (group.t) groupBox.appendChild(el("div", "nav-group-title", group.t));
        renderTree(group.c || [group], groupBox);
        box.appendChild(groupBox);
      });
      sidebarNav.appendChild(box);
    });
  }

  // ---------------------------------------------------------------- drawer
  var menuBtn = document.getElementById("menu-btn");
  var drawerClose = document.getElementById("drawer-close");
  var mask = document.getElementById("mask");

  function openDrawer() {
    if (!sidebar) return;
    sidebar.classList.add("open");
    if (mask) mask.classList.add("show");
  }

  function closeDrawer() {
    if (!sidebar) return;
    sidebar.classList.remove("open");
    if (mask) mask.classList.remove("show");
  }

  if (menuBtn) {
    menuBtn.addEventListener("click", function () {
      if (sidebar && sidebar.classList.contains("open")) closeDrawer();
      else openDrawer();
    });
  }
  if (drawerClose) drawerClose.addEventListener("click", closeDrawer);
  if (mask) mask.addEventListener("click", closeDrawer);
  if (sidebar) {
    sidebar.addEventListener("click", function (event) {
      if (event.target.closest("a") && window.innerWidth <= 900) closeDrawer();
    });
  }

  // 顶栏入口：搜索聚焦侧栏输入框，首页回到目录，退出交给 Android 宿主。
  var searchOpen = document.getElementById("search-open");
  if (searchOpen) {
    searchOpen.addEventListener("click", function () {
      if (window.innerWidth <= 900) openDrawer();
      var input = document.getElementById("nav-search-input");
      if (input) input.focus();
    });
  }

  var closeBtn = document.getElementById("manual-close");
  if (closeBtn) {
    closeBtn.addEventListener("click", function () {
      if (window.AndroidManual && window.AndroidManual.closeManual) {
        window.AndroidManual.closeManual();
      } else {
        window.close();
      }
    });
  }

  // ---------------------------------------------------------------- search
  var resultsBox = document.getElementById("search-results");
  var indexBody = document.getElementById("index-body");

  function escapeHtml(value) {
    return String(value)
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;");
  }

  function highlight(text, query) {
    var safe = escapeHtml(text);
    if (!query) return safe;
    var lower = safe.toLowerCase();
    var ql = query.toLowerCase();
    var out = "";
    var index = 0;
    while (true) {
      var found = lower.indexOf(ql, index);
      if (found < 0) break;
      out += safe.slice(index, found) + "<mark>" + safe.slice(found, found + ql.length) + "</mark>";
      index = found + ql.length;
    }
    return out + safe.slice(index);
  }

  function runSearch(query) {
    if (!resultsBox || !window.MANUAL_SEARCH) return;
    var q = (query || "").trim();
    if (!q) {
      resultsBox.hidden = true;
      resultsBox.innerHTML = "";
      if (indexBody) indexBody.hidden = false;
      return;
    }
    var ql = q.toLowerCase();
    var hits = [];
    window.MANUAL_SEARCH.forEach(function (row) {
      var title = (row.t || "").toLowerCase();
      var body = (row.x || "").toLowerCase();
      var inTitle = title.indexOf(ql) >= 0;
      var inBody = body.indexOf(ql) >= 0;
      if (!inTitle && !inBody) return;
      var score = (inTitle ? 100 : 0) + (title.indexOf(ql) === 0 ? 20 : 0);
      hits.push({ row: row, score: score });
    });
    hits.sort(function (a, b) { return b.score - a.score; });
    if (indexBody) indexBody.hidden = true;
    resultsBox.hidden = false;
    resultsBox.innerHTML = "";

    resultsBox.appendChild(el("h2", null, "搜索结果 · " + hits.length + " 篇"));

    if (!hits.length) {
      resultsBox.appendChild(el("div", "no-result", "没有找到包含「" + q + "」的内容。"));
      return;
    }

    hits.slice(0, 30).forEach(function (hit) {
      var row = hit.row;
      var link = el("a", "result");
      link.href = root + row.p;

      var titleLine = el("div");
      titleLine.innerHTML =
        '<span class="r-title">' + highlight(row.t, q) + "</span>" +
        '<span class="r-meta">' + escapeHtml(row.s || "") + "</span>";
      link.appendChild(titleLine);

      var body = row.x || "";
      var at = body.toLowerCase().indexOf(ql);
      var snippet = at >= 0 ? body.slice(Math.max(0, at - 50), at + 110) : body.slice(0, 140);
      var snippetNode = el("div", "r-snippet");
      snippetNode.innerHTML = (at >= 0 && !body.slice(0, 1).match(/[。\n]/) ? "…" : "") +
        highlight(snippet, q) + (snippet.length >= 140 ? "…" : "");
      link.appendChild(snippetNode);
      resultsBox.appendChild(link);
    });
  }

  function handleSearch(input) {
    var q = input.value.trim();
    if (isHome) {
      if (q) history.replaceState(null, "", "?q=" + encodeURIComponent(q));
      else history.replaceState(null, "", "index.html");
      runSearch(q);
      closeDrawer();
    } else {
      location.href = root + "index.html" + (q ? "?q=" + encodeURIComponent(q) : "");
    }
  }

  ["nav-search-form", "search-form"].forEach(function (id) {
    var form = document.getElementById(id);
    if (!form) return;
    form.addEventListener("submit", function (event) {
      event.preventDefault();
      var input = form.querySelector('input[type="search"]');
      if (input) handleSearch(input);
    });
  });

  if (isHome) {
    var initial = new URLSearchParams(location.search).get("q") || "";
    var heroInput = document.getElementById("search-input");
    var navInput = document.getElementById("nav-search-input");
    if (initial) {
      if (heroInput) heroInput.value = initial;
      if (navInput) navInput.value = initial;
      runSearch(initial);
    }
  }

  // ---------------------------------------------------------------- theme
  if (!document.documentElement.dataset.theme) {
    var dark = window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches;
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  }
})();
