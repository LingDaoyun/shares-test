import type { Config } from 'tailwindcss'

// 简明轻快 · 天蓝 + 白 主题
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        // 主色：蓝色系
        brand: {
          50: '#EFF6FF',
          100: '#DBEAFE',
          200: '#BFDBFE',
          300: '#93C5FD',
          400: '#60A5FA',
          500: '#3B82F6', // primary
          600: '#2563EB', // hover
          700: '#1D4ED8'
        },
        sky: {
          400: '#38BDF8',
          500: '#0EA5E9' // 强调/链接/图表
        },
        // 中性：浅灰底
        canvas: '#F8FAFC',
        ink: {
          900: '#0F172A', // 主文本
          600: '#475569', // 次文本
          400: '#94A3B8'  // 弱文本
        },
        line: {
          DEFAULT: '#E2E8F0',
          soft: '#F1F5F9'
        },
        // 语义
        up: '#DC2626',   // A股 红涨
        down: '#16A34A', // A股 绿跌
        success: '#10B981',
        warning: '#F59E0B',
        danger: '#EF4444'
      },
      fontFamily: {
        sans: ['Inter', '"Noto Sans SC"', '"PingFang SC"', '"Microsoft YaHei"', 'sans-serif']
      },
      borderRadius: {
        xl: '12px',
        '2xl': '16px'
      },
      boxShadow: {
        card: '0 1px 2px 0 rgba(15, 23, 42, 0.04), 0 1px 3px 0 rgba(15, 23, 42, 0.06)',
        soft: '0 4px 12px -2px rgba(15, 23, 42, 0.06)',
        float: '0 8px 24px -6px rgba(15, 23, 42, 0.10)'
      },
      keyframes: {
        'fade-in': {
          '0%': { opacity: '0', transform: 'translateY(4px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' }
        },
        'toast-in': {
          '0%': { opacity: '0', transform: 'translateY(-25vh)' },
          '55%': { opacity: '1', transform: 'translateY(6px)' },
          '75%': { opacity: '1', transform: 'translateY(-2px)' },
          '100%': { opacity: '1', transform: 'translateY(0)' }
        },
        'toast-sweep': {
          '0%': { left: '-40%' },
          '100%': { left: '100%' }
        }
      },
      animation: {
        'fade-in': 'fade-in 0.25s ease-out',
        'toast-in': 'toast-in 0.55s cubic-bezier(0.22, 1, 0.36, 1)',
        'toast-sweep': 'toast-sweep 1.2s ease-in-out infinite'
      }
    }
  },
  plugins: []
} satisfies Config
