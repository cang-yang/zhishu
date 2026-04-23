type ThemePreset = UnionKey.ThemePreset;

function cloneThemeSettings(settings: App.Theme.ThemeSetting): App.Theme.ThemeSetting {
  return JSON.parse(JSON.stringify(settings)) as App.Theme.ThemeSetting;
}

const sharedThemeSettings = {
  grayscale: false,
  colourWeakness: false,
  recommendColor: true,
  isInfoFollowPrimary: true,
  resetCacheStrategy: 'close' as const,
  layout: { mode: 'vertical' as UnionKey.ThemeLayoutMode, scrollMode: 'content' as const, reverseHorizontalMix: false },
  page: { animate: true, animateMode: 'fade-slide' },
  header: { height: 56, breadcrumb: { visible: false, showIcon: true }, multilingual: { visible: false } },
  tab: { visible: false, cache: true, height: 40, mode: 'chrome' },
  fixedHeaderAndTab: true,
  sider: {
    inverted: false,
    width: 280,
    collapsedWidth: 80,
    mixWidth: 96,
    mixCollapsedWidth: 72,
    mixChildMenuWidth: 216
  },
  footer: { visible: false, fixed: false, height: 48, right: true }
} satisfies Omit<App.Theme.ThemeSetting, 'themePreset' | 'themeScheme' | 'themeColor' | 'otherColor' | 'watermark' | 'tokens'>;

const themePresetRecord: Record<ThemePreset, App.Theme.ThemeSetting> = {
  'aether-light': {
    themePreset: 'aether-light',
    themeScheme: 'light',
    themeColor: '#4f7cff',
    otherColor: { info: '#4f7cff', success: '#22a06b', warning: '#f59e0b', error: '#ef4444' },
    ...sharedThemeSettings,
    watermark: { visible: false, text: '智枢 AetherDesk' },
    tokens: {
      light: {
        colors: {
          container: 'rgb(255, 255, 255)',
          layout: 'rgb(247, 248, 250)',
          inverted: 'rgb(15, 23, 42)',
          'base-text': 'rgb(17, 24, 39)'
        },
        boxShadow: {
          header: '0 8px 24px rgb(15, 23, 42, 0.05)',
          sider: '0 12px 30px rgb(15, 23, 42, 0.05)',
          tab: '0 8px 20px rgb(15, 23, 42, 0.05)'
        }
      },
      dark: {
        colors: {
          container: 'rgb(17, 24, 39)',
          layout: 'rgb(15, 23, 42)',
          inverted: 'rgb(2, 6, 23)',
          'base-text': 'rgb(226, 232, 240)'
        },
        boxShadow: {
          header: '0 10px 28px rgb(2, 6, 23, 0.26)',
          sider: '0 16px 36px rgb(2, 6, 23, 0.3)',
          tab: '0 10px 24px rgb(2, 6, 23, 0.26)'
        }
      }
    }
  },
  'aether-dark': {
    themePreset: 'aether-dark',
    themeScheme: 'dark',
    themeColor: '#7c96ff',
    otherColor: { info: '#7c96ff', success: '#34d399', warning: '#fbbf24', error: '#f87171' },
    ...sharedThemeSettings,
    sider: {
      ...sharedThemeSettings.sider,
      inverted: true
    },
    watermark: { visible: false, text: '智枢 AetherDesk' },
    tokens: {
      light: {
        colors: {
          container: 'rgb(255, 255, 255)',
          layout: 'rgb(247, 248, 250)',
          inverted: 'rgb(15, 23, 42)',
          'base-text': 'rgb(17, 24, 39)'
        },
        boxShadow: {
          header: '0 8px 24px rgb(15, 23, 42, 0.05)',
          sider: '0 12px 30px rgb(15, 23, 42, 0.05)',
          tab: '0 8px 20px rgb(15, 23, 42, 0.05)'
        }
      },
      dark: {
        colors: {
          container: 'rgb(16, 18, 24)',
          layout: 'rgb(9, 11, 16)',
          inverted: 'rgb(2, 6, 23)',
          'base-text': 'rgb(241, 245, 249)'
        },
        boxShadow: {
          header: '0 14px 40px rgb(0, 0, 0, 0.4)',
          sider: '0 16px 40px rgb(0, 0, 0, 0.42)',
          tab: '0 10px 26px rgb(0, 0, 0, 0.32)'
        }
      }
    }
  },
  system: {
    themePreset: 'system',
    themeScheme: 'auto',
    themeColor: '#5b7cff',
    otherColor: { info: '#5b7cff', success: '#10b981', warning: '#f59e0b', error: '#ef4444' },
    ...sharedThemeSettings,
    watermark: { visible: false, text: '智枢 AetherDesk' },
    tokens: {
      light: {
        colors: {
          container: 'rgb(255, 255, 255)',
          layout: 'rgb(245, 247, 250)',
          inverted: 'rgb(15, 23, 42)',
          'base-text': 'rgb(17, 24, 39)'
        },
        boxShadow: {
          header: '0 8px 24px rgb(15, 23, 42, 0.05)',
          sider: '0 12px 28px rgb(15, 23, 42, 0.05)',
          tab: '0 8px 20px rgb(15, 23, 42, 0.05)'
        }
      },
      dark: {
        colors: {
          container: 'rgb(15, 23, 42)',
          layout: 'rgb(2, 6, 23)',
          inverted: 'rgb(15, 23, 42)',
          'base-text': 'rgb(226, 232, 240)'
        },
        boxShadow: {
          header: '0 12px 30px rgb(2, 6, 23, 0.28)',
          sider: '0 14px 32px rgb(2, 6, 23, 0.26)',
          tab: '0 10px 24px rgb(2, 6, 23, 0.24)'
        }
      }
    }
  }
};

export function getThemePresetByScheme(themeScheme: UnionKey.ThemeScheme = 'auto'): ThemePreset {
  if (themeScheme === 'light') return 'aether-light';
  if (themeScheme === 'dark') return 'aether-dark';
  return 'system';
}

export function createThemeSettingsByPreset(preset: ThemePreset = 'system') {
  return cloneThemeSettings(themePresetRecord[preset] || themePresetRecord.system);
}

/** Default theme settings */
export const themeSettings: App.Theme.ThemeSetting = createThemeSettingsByPreset('system');

/**
 * Override theme settings
 *
 * If publish new version, use `overrideThemeSettings` to override certain theme settings
 */
export const overrideThemeSettings: Partial<App.Theme.ThemeSetting> = {};
