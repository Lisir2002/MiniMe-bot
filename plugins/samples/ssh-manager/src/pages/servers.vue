<template>
  <div id="app" class="minibot-page" v-cloak>
    <header class="minibot-page-header">
      <minibot-icon-button label="返回" @click="handleBackButton">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="19" y1="12" x2="5" y2="12"></line>
          <polyline points="12 19 5 12 12 5"></polyline>
        </svg>
      </minibot-icon-button>
      <h1 class="minibot-page-title">{{ pageTitle }}</h1>
      <minibot-icon-button v-if="view === 'list'" label="新建" @click="openEdit(null)">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
          stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <line x1="12" y1="5" x2="12" y2="19"></line>
          <line x1="5" y1="12" x2="19" y2="12"></line>
        </svg>
      </minibot-icon-button>
      <button v-if="view === 'edit'" class="minibot-text-button" :disabled="saving" @click="save">保存</button>
    </header>

    <div class="page-content">
      <section v-show="view === 'list'" class="view">
        <minibot-group title="SSH 服务器">
          <template v-if="servers.length">
            <template v-for="(server, index) in servers" :key="server.id">
              <div v-if="index > 0" class="minibot-divider"></div>
              <minibot-swipe-row @delete="remove(server)">
                <minibot-row
                  :title="server.name || server.host"
                  :subtitle="server.username + '@' + server.host + ':' + server.port"
                  :badge="statusLabel(server.status)"
                  @click="openDetail(server)"></minibot-row>
              </minibot-swipe-row>
            </template>
          </template>
          <minibot-empty v-else text="暂无 SSH 服务器" />
        </minibot-group>
        <p class="hint">App 启动时会自动连接启用项；连接由常驻插件进程维护。</p>
        <div class="warning">
          密码和私钥路径明文保存在插件数据目录 <code>data://servers.json</code>，不会进入 Mini-AIBOT 备份。建议使用受限账号，私钥放在插件数据目录。
        </div>
      </section>

      <section v-show="view === 'detail'" class="view">
        <minibot-group title="连接信息" :collapsible="false">
          <div class="minibot-info-row"><span>名称</span><span>{{ current ? current.name : '' }}</span></div>
          <div class="minibot-divider minibot-divider-flat"></div>
          <div class="minibot-info-row"><span>地址</span><span>{{ current ? current.username + '@' + current.host + ':' + current.port : '' }}</span></div>
          <div class="minibot-divider minibot-divider-flat"></div>
          <div class="minibot-info-row"><span>认证</span><span>{{ current && current.auth === 'password' ? '密码' : '私钥' }}</span></div>
          <div class="minibot-divider minibot-divider-flat"></div>
          <div class="minibot-info-row"><span>状态</span><span :class="['status', current ? current.status : 'offline']">{{ statusLabel(current ? current.status : 'offline') }}</span></div>
          <div class="minibot-divider minibot-divider-flat"></div>
          <div class="minibot-info-row"><span>启动连接</span><span>{{ current && current.enabled !== false ? '是' : '否' }}</span></div>
        </minibot-group>
        <p v-if="current && current.error" class="warning">{{ current.error }}</p>
        <div class="action-bar">
          <minibot-button :disabled="busy || !current || current.status === 'online'" @click="connect(current)">连接</minibot-button>
          <minibot-button :disabled="busy || !current || current.status === 'offline'" @click="disconnect(current)">断开</minibot-button>
          <minibot-button @click="openEdit(current)">编辑</minibot-button>
        </div>
      </section>

      <section v-show="view === 'edit'" class="view">
        <minibot-group title="基本信息" :collapsible="false">
          <minibot-field label="名称">
            <minibot-input v-model="form.name" placeholder="production" />
          </minibot-field>
          <minibot-field label="Host">
            <minibot-input v-model="form.host" placeholder="192.168.1.10" />
          </minibot-field>
          <minibot-field label="端口">
            <minibot-input v-model.number="form.port" placeholder="22" />
          </minibot-field>
          <minibot-field label="用户名">
            <minibot-input v-model="form.username" placeholder="root" />
          </minibot-field>
          <minibot-field label="认证方式">
            <minibot-segmented v-model="form.auth" :options="authOptions" />
          </minibot-field>
          <minibot-field v-if="form.auth === 'key'" label="私钥路径">
            <minibot-input v-model="form.privateKeyPath" placeholder="data://id_ed25519" />
          </minibot-field>
          <minibot-field v-if="form.auth === 'key'" label="私钥口令（可选）">
            <minibot-input v-model="form.passphrase" type="password" />
          </minibot-field>
          <minibot-field v-if="form.auth === 'password'" label="密码">
            <minibot-input v-model="form.password" type="password" />
          </minibot-field>
          <div class="minibot-scope-row">
            <span class="minibot-scope-label">App 启动时连接</span>
            <minibot-switch v-model="form.enabled"></minibot-switch>
          </div>
        </minibot-group>
        <p v-if="form.auth === 'key'" class="hint">私钥建议使用 data:// 路径，文件放在插件数据目录，避免容器重建后丢失。</p>
        <p v-if="error" class="minibot-error">{{ error }}</p>
      </section>
    </div>
  </div>
</template>

<script lang="ts">
import { defineComponent } from 'vue';

interface ServerForm {
  id: string;
  name: string;
  host: string;
  port: number;
  username: string;
  auth: string;
  privateKeyPath: string;
  passphrase: string;
  password: string;
  enabled: boolean;
  status?: string;
  error?: string;
}

interface ServersData {
  view: 'list' | 'edit' | 'detail';
  servers: ServerForm[];
  current: ServerForm | null;
  form: ServerForm;
  editingId: string;
  busy: boolean;
  saving: boolean;
  error: string;
}

function emptyForm(): ServerForm {
      return {
        id: '',
        name: '',
        host: '',
        port: 22,
        username: 'root',
        auth: 'key',
        privateKeyPath: 'data://id_ed25519',
        passphrase: '',
        password: '',
        enabled: true,
        status: 'offline',
        error: ''
      };
    }

    function plainServer(server: Partial<ServerForm>): ServerForm {
      return {
        id: server.id || '',
        name: server.name || '',
        host: server.host || '',
        port: Number(server.port) || 22,
        username: server.username || '',
        auth: server.auth === 'password' ? 'password' : 'key',
        privateKeyPath: server.privateKeyPath || '',
        passphrase: server.passphrase || '',
        password: server.password || '',
        enabled: server.enabled !== false
      };
    }

export default defineComponent({
      data(): ServersData {
        return {
          view: 'list',
          servers: [],
          current: null,
          form: emptyForm(),
          editingId: '',
          busy: false,
          saving: false,
          error: ''
        };
      },
      computed: {
        pageTitle: function () {
          if (this.view === 'edit') return this.editingId ? '编辑服务器' : '新建服务器';
          if (this.view === 'detail') return this.current ? (this.current.name || this.current.host) : 'SSH 服务器';
          return 'SSH 服务器';
        },
        authOptions: function () {
          return [
            { value: 'key', label: '私钥' },
            { value: 'password', label: '密码' }
          ];
        }
      },
      mounted: function () {
        var self = this;
        this.load().then(function () { return self.refreshStatus(); });
        if (window.minibot && minibot.ui && minibot.ui.onBack) {
          minibot.ui.onBack(this.handleBack);
        }
        if (window.minibot && minibot.events) {
          minibot.events.on(function (event) {
            if (!event || event.name !== 'ssh.status' || !event.payload) return;
            var payload = event.payload;
            var server = self.servers.find(function (item) { return item.id === payload.serverId; });
            if (!server) return;
            server.status = payload.status;
            server.error = payload.error || '';
            if (self.current && self.current.id === server.id) self.current = server;
          });
        }
      },
      methods: {
        load: function () {
          var self = this;
          return minibot.fs.read('data://servers.json').then(function (res) {
            var parsed = {};
            try { parsed = res && res.content ? JSON.parse(res.content) : {}; } catch (e) {}
            self.servers = (Array.isArray(parsed.servers) ? parsed.servers : []).map(function (server: Partial<ServerForm>) {
              var item = plainServer(server);
              item.status = 'offline';
              return item;
            });
          }).catch(function () {
            self.servers = [];
          });
        },
        mergeStatus: function (list: Partial<ServerForm>[]) {
          var byId = {};
          (list || []).forEach(function (server) { byId[server.id] = server; });
          this.servers.forEach(function (server) {
            var live = byId[server.id];
            if (!live) return;
            server.status = live.status || 'offline';
            server.error = live.error || '';
          });
          if (this.current) {
            var currentId = this.current.id;
            var updated = this.servers.find(function (server) { return server.id === currentId; });
            if (updated) this.current = updated;
          }
        },
        refreshStatus: function () {
          var self = this;
          return minibot.invoke('ui.status').then(function (res) {
            if (res && Array.isArray(res.servers)) self.mergeStatus(res.servers);
          }).catch(function (e) {
            self.error = e.message || String(e);
          });
        },
        saveConfig: function () {
          return minibot.fs.write('data://servers.json', JSON.stringify({ servers: this.servers }, null, 2));
        },
        openDetail: function (server: ServerForm) {
          this.current = server;
          this.view = 'detail';
          this.error = '';
        },
        openEdit: function (server?: ServerForm) {
          this.error = '';
          if (server) {
            this.editingId = server.id;
            this.form = Object.assign(emptyForm(), server);
            delete this.form.status;
            delete this.form.error;
          } else {
            this.editingId = '';
            this.form = emptyForm();
          }
          this.view = 'edit';
        },
        handleBack: function () {
          if (this.view === 'list') return false;
          if (this.view === 'edit') {
            this.view = this.editingId ? 'detail' : 'list';
          } else {
            this.view = 'list';
          }
          return true;
        },
        handleBackButton: function () {
          if (!this.handleBack()) {
            minibot.ui.close().catch(function () {});
          }
        },
        save: function () {
          var self = this;
          this.error = '';
          if (!this.form.name || !this.form.host || !this.form.username) {
            this.error = '名称、Host、用户名不能为空';
            return;
          }
          this.saving = true;
          this.form.port = Number(this.form.port) || 22;
          var data = plainServer(this.form);
          var index = this.servers.findIndex(function (item) { return item.id === self.editingId; });
          if (index >= 0) {
            data.id = this.editingId;
            this.servers.splice(index, 1, data);
          } else {
            data.id = 'srv-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 7);
            this.servers.push(data);
          }
          this.saveConfig()
            .then(function () { return minibot.invoke('ui.reload'); })
            .then(function (res) {
              if (res && Array.isArray(res.servers)) self.mergeStatus(res.servers);
              self.view = 'list';
              minibot.toast('已保存');
            })
            .catch(function (e) { self.error = e.message || String(e); })
            .then(function () { self.saving = false; });
        },
        remove: function (server: ServerForm) {
          var self = this;
          this.servers = this.servers.filter(function (item) { return item.id !== server.id; });
          this.current = null;
          this.view = 'list';
          this.saveConfig()
            .then(function () {
              minibot.invoke('ui.disconnect', { serverId: server.id }).catch(function () {});
              return self.refreshStatus();
            })
            .catch(function (e) { self.error = e.message || String(e); });
        },
        connect: function (server?: ServerForm) {
          var self = this;
          if (!server) return;
          this.busy = true;
          minibot.invoke('ui.connect', { serverId: server.id })
            .then(function () { return self.refreshStatus(); })
            .catch(function (e) { minibot.toast(e.message || String(e)); })
            .then(function () { self.busy = false; });
        },
        disconnect: function (server?: ServerForm) {
          var self = this;
          if (!server) return;
          minibot.invoke('ui.disconnect', { serverId: server.id })
            .then(function () { return self.refreshStatus(); })
            .catch(function (e) { minibot.toast(e.message || String(e)); });
        },
        statusLabel: function (status: string) {
          if (status === 'online') return '已连接';
          if (status === 'connecting') return '连接中';
          return '未连接';
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
      gap: 8px;
      width: 100%;
      max-width: 860px;
      margin: 0 auto;
      padding: 0 16px 28px;
    }
    .hint {
      margin: 4px 4px 0;
      color: var(--minibot-on-surface-variant);
      font-size: calc(12px * var(--minibot-font-scale));
    }
    .warning {
      margin: 8px 0;
      padding: 10px 12px;
      border-radius: 10px;
      background: color-mix(in srgb, var(--minibot-error) 12%, transparent);
      color: var(--minibot-error);
      font-size: calc(12px * var(--minibot-font-scale));
    }
    .action-bar {
      display: flex;
      gap: 8px;
      flex-wrap: wrap;
      padding: 8px 0;
    }
    .status {
      display: inline-block;
      padding: 1px 8px;
      border-radius: 999px;
      font-size: calc(11px * var(--minibot-font-scale));
      white-space: nowrap;
    }
    .status.online { background: color-mix(in srgb, var(--minibot-success) 18%, transparent); color: var(--minibot-success); }
    .status.connecting { background: color-mix(in srgb, var(--minibot-warning) 18%, transparent); color: var(--minibot-warning); }
    .status.offline { background: color-mix(in srgb, var(--minibot-error) 14%, transparent); color: var(--minibot-error); }
</style>
