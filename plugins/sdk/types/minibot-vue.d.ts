/**
 * Mini-AIBOT 插件页面组件库（`/__minibot/minibot-ui-vue.js`）的模板类型声明。
 *
 * 组件库在运行时挂到全局 `window.MiniBotUI`，页面通过 `app.use(MiniBotUI)` 注册。
 * 本文件由 `@jieapi/plugin-sdk/page/vue` 导出，把同名组件并入 Vue 的 `GlobalComponents`，
 * 让 Volar / vue-tsc 在模板里补全 `<MiniBotButton>`、`<minibot-button>` 等标签与它们的 props。
 *
 * 需要项目安装 `vue`（仅用于类型，构建时仍使用 `/__minibot/vue.global.prod.js`）。
 */

import type {
  ComponentOptionsMixin,
  ComputedOptions,
  DefineComponent,
  EmitsOptions,
  MethodOptions,
} from 'vue';

export type { PermissionRequest, PermissionDecision } from './host';

type MiniBotOption = { label: string; value: string };

type MiniBotComponent<Props, Emits extends EmitsOptions> = DefineComponent<
  Props,
  {},
  {},
  ComputedOptions,
  MethodOptions,
  ComponentOptionsMixin,
  ComponentOptionsMixin,
  Emits
>;

interface MiniBotBadgeProps {
  text?: string;
  kind?: string;
}

interface MiniBotGroupProps {
  title?: string;
  collapsible?: boolean;
  /** 支持 `v-model:expanded`。 */
  expanded?: boolean;
}

interface MiniBotRowProps {
  title?: string;
  subtitle?: string;
  badge?: string;
  badgeKind?: string;
  /** `MiniBotUI.icons` 中的图标名。 */
  icon?: string;
  chevron?: boolean;
  selectable?: boolean;
  selected?: boolean;
}

interface MiniBotFieldProps {
  label?: string;
}

interface MiniBotSegmentedProps {
  modelValue?: string;
  options?: MiniBotOption[];
  disabled?: boolean;
}

interface MiniBotButtonProps {
  /** 同 `minibot-button-{variant}` 的样式变体。 */
  variant?: string;
  compact?: boolean;
  disabled?: boolean;
}

interface MiniBotEmptyProps {
  text?: string;
}

interface MiniBotMarkdownProps {
  text?: string;
}

interface MiniBotIconButtonProps {
  /** 无障碍标签，同时用于 title。 */
  label?: string;
}

interface MiniBotSwipeRowProps {
  disabled?: boolean;
  actionText?: string;
}

interface MiniBotSwitchProps {
  modelValue?: boolean;
  disabled?: boolean;
}

interface MiniBotInputProps {
  modelValue?: string;
  placeholder?: string;
  disabled?: boolean;
  type?: string;
}

interface MiniBotTextareaProps {
  modelValue?: string;
  placeholder?: string;
  disabled?: boolean;
}

interface MiniBotSelectProps {
  modelValue?: string;
  options?: MiniBotOption[];
  disabled?: boolean;
}

interface MiniBotDividerProps {
  flat?: boolean;
}

interface MiniBotInfoRowProps {
  label?: string;
  value?: string;
}

interface MiniBotBottomSheetProps {
  modelValue?: boolean;
  title?: string;
}

interface MiniBotDialogProps {
  modelValue?: boolean;
  title?: string;
  message?: string;
  confirmText?: string;
  cancelText?: string;
  confirmDisabled?: boolean;
}

declare module 'vue' {
  export interface GlobalComponents {
    MiniBotBadge: DefineComponent<MiniBotBadgeProps>;
    MiniBotCard: DefineComponent;
    MiniBotGroup: MiniBotComponent<MiniBotGroupProps, {
      'update:expanded': (expanded: boolean) => void;
    }>;
    MiniBotRow: MiniBotComponent<MiniBotRowProps, {
      click: (event: MouseEvent) => void;
    }>;
    MiniBotField: DefineComponent<MiniBotFieldProps>;
    MiniBotSegmented: MiniBotComponent<MiniBotSegmentedProps, {
      'update:modelValue': (value: string) => void;
    }>;
    MiniBotButton: MiniBotComponent<MiniBotButtonProps, {
      click: (event: MouseEvent) => void;
    }>;
    MiniBotEmpty: DefineComponent<MiniBotEmptyProps>;
    MiniBotMarkdown: DefineComponent<MiniBotMarkdownProps>;
    MiniBotIconButton: MiniBotComponent<MiniBotIconButtonProps, {
      click: (event: MouseEvent) => void;
    }>;
    MiniBotSwipeRow: MiniBotComponent<MiniBotSwipeRowProps, {
      delete: () => void;
    }>;
    MiniBotSwitch: MiniBotComponent<MiniBotSwitchProps, {
      'update:modelValue': (value: boolean) => void;
    }>;
    MiniBotInput: MiniBotComponent<MiniBotInputProps, {
      'update:modelValue': (value: string) => void;
    }>;
    MiniBotTextarea: MiniBotComponent<MiniBotTextareaProps, {
      'update:modelValue': (value: string) => void;
    }>;
    MiniBotSelect: MiniBotComponent<MiniBotSelectProps, {
      'update:modelValue': (value: string) => void;
    }>;
    MiniBotDivider: DefineComponent<MiniBotDividerProps>;
    MiniBotInfoRow: DefineComponent<MiniBotInfoRowProps>;
    MiniBotBottomSheet: MiniBotComponent<MiniBotBottomSheetProps, {
      'update:modelValue': (value: boolean) => void;
    }>;
    MiniBotDialog: MiniBotComponent<MiniBotDialogProps, {
      'update:modelValue': (value: boolean) => void;
      confirm: () => void;
      cancel: () => void;
    }>;
  }
}

export {};
