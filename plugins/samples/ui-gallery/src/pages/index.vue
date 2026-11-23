<template>
  <div id="app" class="minibot-page" v-cloak>
    <template v-if="contextLoaded">
      <header class="minibot-page-header">
        <minibot-icon-button v-if="canClose" label="返回" @click="closePage">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <line x1="19" y1="12" x2="5" y2="12"></line>
            <polyline points="12 19 5 12 12 5"></polyline>
          </svg>
        </minibot-icon-button>
        <h1 class="minibot-page-title">{{ pageTitle }}</h1>
        <minibot-icon-button label="说明" @click="openSheet">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
            stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <circle cx="12" cy="12" r="10"></circle>
            <line x1="12" y1="16" x2="12" y2="12"></line>
            <line x1="12" y1="8" x2="12.01" y2="8"></line>
          </svg>
        </minibot-icon-button>
      </header>

      <div class="page-content">
        <router-view v-slot="{ Component }">
          <component :is="Component" @open-sheet="openSheet" />
        </router-view>
      </div>

      <minibot-bottom-sheet v-model="sheetOpen" :title="sheetTitle">
        <template v-if="isConfigPage">
          <p class="minibot-text">配置内容持久化存储于 <code>data://config.json</code>，属于插件私有存储，卸载插件时一并清除。</p>
          <minibot-button variant="primary" @click="sheetOpen = false">我知道了</minibot-button>
        </template>
        <template v-else>
          <p class="minibot-text">这是宿主提供的底部弹窗组件：拖拽顶部小白条下拉可关闭，点击遮罩或右上角关闭按钮也可以退出；宽屏下会自动变成居中对话框。内容区可滚动，尺寸与设置页的
            AdaptiveModalBottomSheet 一致。</p>
          <minibot-button variant="primary" @click="sheetOpen = false">关闭</minibot-button>
        </template>
      </minibot-bottom-sheet>
    </template>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface GalleryIndexData {
  contextLoaded: boolean;
  canClose: boolean;
  isConfigPage: boolean;
  sheetOpen: boolean;
}

export default defineComponent({
  data(): GalleryIndexData {
    return {
      contextLoaded: false,
      canClose: true,
      isConfigPage: false,
      sheetOpen: false
    };
  },
  computed: {
    pageTitle: function () {
      return this.isConfigPage ? '插件配置' : '组件演示';
    },
    sheetTitle: function () {
      return this.isConfigPage ? '配置说明' : '底部弹窗';
    }
  },
  mounted: function () {
    this.loadContext();
  },
  methods: {
    loadContext: function () {
      var self = this;
      if (!window.minibot || typeof minibot.context !== 'function') {
        self.contextLoaded = true;
        return;
      }
      minibot.context().then(function (ctx) {
        self.isConfigPage = !!ctx && ctx.pageId === 'config';
        self.canClose = !self.isConfigPage;
        var path = self.isConfigPage ? '/config' : '/gallery';
        if (self.$route.path !== path) return self.$router.replace(path);
      }).catch(function () {
        self.canClose = true;
      }).finally(function () {
        self.contextLoaded = true;
      });
    },
    openSheet: function () {
      this.sheetOpen = true;
    },
    closePage: function () {
      if (window.minibot && minibot.ui && minibot.ui.close) {
        minibot.ui.close().catch(function () {});
      }
    }
  }
});
</script>

<style>
* { box-sizing: border-box; }
    html, body { margin: 0; padding: 0; }
    [hidden], [v-cloak] { display: none !important; }
    body {
      background: var(--minibot-page-bg);
      color: var(--minibot-on-surface);
      font-family: var(--minibot-font-sans);
      font-size: calc(14px * var(--minibot-font-scale));
      line-height: 1.5;
      -webkit-text-size-adjust: 100%;
    }
    .page-content {
      display: flex;
      flex-direction: column;
      gap: 12px;
      width: 100%;
      max-width: 860px;
      margin: 0 auto;
      padding: 12px 16px 36px;
    }
    .demo-row {
      display: flex;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      padding: 12px 16px;
    }
    .demo-label {
      color: var(--minibot-on-surface);
      font-size: calc(15px * var(--minibot-font-scale));
      font-weight: 500;
    }
    .demo-hint {
      margin: 4px 0 0;
      color: var(--minibot-on-surface-variant);
      font-size: calc(13px * var(--minibot-font-scale));
    }
</style>
