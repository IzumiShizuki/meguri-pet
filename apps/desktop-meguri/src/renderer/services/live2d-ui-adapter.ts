import { defineComponent, h, onBeforeUnmount, onMounted, ref } from 'vue'

// AIRI's renderer only needs the viewport and theme seams from its UI package.
// Keeping these native avoids pulling a second application shell into the pet.
export const Screen = defineComponent({
  inheritAttrs: false,
  setup(_props, { slots, attrs }) {
    const element = ref<HTMLElement>()
    const width = ref(1)
    const height = ref(1)
    const observer = new ResizeObserver((entries) => {
      width.value = Math.max(1, entries[0]?.contentRect.width ?? 1)
      height.value = Math.max(1, entries[0]?.contentRect.height ?? 1)
    })
    onMounted(() => { if (element.value) observer.observe(element.value) })
    onBeforeUnmount(() => observer.disconnect())
    return () => h('div', { ...attrs, ref: element, style: 'position:relative;width:100%;height:100%;' }, slots.default?.({ width: width.value, height: height.value }))
  },
})

export function useTheme() {
  const isDark = ref(document.documentElement.dataset.theme === 'dark' || document.documentElement.classList.contains('dark'))
  const observer = new MutationObserver(() => { isDark.value = document.documentElement.dataset.theme === 'dark' || document.documentElement.classList.contains('dark') })
  onMounted(() => observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class', 'data-theme'] }))
  onBeforeUnmount(() => observer.disconnect())
  return { isDark }
}
