<template>
  <div class="config-view">
    <minibot-group title="服务接入凭证">
      <div class="field-block">
        <div class="field-title">API 端点 (Endpoint)</div>
        <minibot-input v-model="config.endpoint" placeholder="https://api.example.com/v1"></minibot-input>
      </div>
      <minibot-divider></minibot-divider>
      <div class="field-block">
        <div class="field-title">API 密钥 (Token)</div>
        <minibot-input v-model="config.apiKey" placeholder="sk-..." type="password"></minibot-input>
      </div>
      <minibot-divider></minibot-divider>
      <div class="field-block">
        <div class="field-title">运行环境</div>
        <minibot-segmented v-model="config.environment" :options="envOptions"></minibot-segmented>
      </div>
    </minibot-group>

    <minibot-group title="行为偏好">
      <div class="demo-row">
        <div>
          <div class="demo-label">自动同步配置</div>
          <p class="demo-hint">每次启动插件时从远程更新规则</p>
        </div>
        <minibot-switch v-model="config.autoSync"></minibot-switch>
      </div>
      <minibot-divider></minibot-divider>
      <div class="demo-row">
        <div>
          <div class="demo-label">详细调试日志</div>
          <p class="demo-hint">向应用日志打印完整的请求与响应包</p>
        </div>
        <minibot-switch v-model="config.verboseLog"></minibot-switch>
      </div>
    </minibot-group>

    <div class="action-bar">
      <minibot-button variant="primary" style="flex: 1;" @click="saveConfig">保存配置</minibot-button>
      <minibot-button variant="secondary" @click="resetConfig">重置</minibot-button>
    </div>

    <p class="demo-hint" style="text-align: center; margin-top: 16px;">
      配置页与组件演示共用 <code>pages/index.html</code>：<code>configPage</code> 打开时路由到 <code>/config</code> 渲染这里。
    </p>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface GalleryConfig {
  endpoint: string;
  apiKey: string;
  environment: string;
  autoSync: boolean;
  verboseLog: boolean;
}

interface ConfigViewData {
  config: GalleryConfig;
}

var DEFAULT_CONFIG: GalleryConfig = {
  endpoint: 'https://api.example.com/v1',
  apiKey: '',
  environment: 'prod',
  autoSync: true,
  verboseLog: false
};

export default defineComponent({
  data(): ConfigViewData {
    return {
      config: { ...DEFAULT_CONFIG }
    };
  },
  computed: {
    envOptions: function () {
      return [
        { value: 'dev', label: '开发' },
        { value: 'test', label: '测试' },
        { value: 'prod', label: '生产' }
      ];
    }
  },
  mounted: function () {
    this.loadConfig();
  },
  methods: {
    loadConfig: function () {
      var self = this;
      if (!window.minibot || !window.minibot.fs) return;
      minibot.fs.read('data://config.json').then(function (res) {
        if (res && res.content) {
          try {
            self.config = Object.assign({}, DEFAULT_CONFIG, JSON.parse(res.content));
          } catch (e) {}
        }
      }).catch(function () {});
    },
    saveConfig: function () {
      var self = this;
      if (!window.minibot || !window.minibot.fs) {
        alert('保存成功 (演示模式)');
        return;
      }
      var json = JSON.stringify(self.config, null, 2);
      minibot.fs.write('data://config.json', json).then(function () {
        minibot.toast('配置已成功保存！');
      }).catch(function (err) {
        minibot.toast('保存失败：' + (err.message || err));
      });
    },
    resetConfig: function () {
      this.config = Object.assign({}, DEFAULT_CONFIG);
      if (window.minibot && minibot.toast) {
        minibot.toast('已重置为默认值');
      }
    }
  }
});
</script>

<style>
.config-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
    .field-block {
      padding: 12px 16px;
    }
    .field-title {
      font-size: calc(13px * var(--minibot-font-scale));
      color: var(--minibot-on-surface-variant);
      margin-bottom: 6px;
    }
    .action-bar {
      margin-top: 8px;
      display: flex;
      gap: 12px;
    }
</style>
