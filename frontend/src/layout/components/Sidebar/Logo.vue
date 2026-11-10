<template>
  <div class="sidebar-logo-container" :class="{ collapse }">
    <transition name="sidebarLogoFade">
      <router-link to="/" class="sidebar-logo-link">
        <span class="brand-symbol" aria-hidden="true">
          <img src="/favicon.svg" class="brand-mark" alt="">
        </span>
        <div v-if="!collapse" class="brand-copy">
          <strong class="sidebar-title">本体 Wiki</strong>
          <span class="brand-caption">ONTOLOGY WORKSPACE</span>
        </div>
      </router-link>
    </transition>
  </div>
</template>

<script>
export default { name: 'SidebarLogo', props: { collapse: { type: Boolean, required: true } } }
</script>

<style lang="scss" scoped>
.sidebarLogoFade-enter-active { transition: opacity .2s; }
.sidebarLogoFade-enter, .sidebarLogoFade-leave-to { opacity: 0; }
.sidebar-logo-container { display:flex; align-items:center; justify-content:flex-start; height:68px; padding:0 22px; overflow:hidden; border-bottom:1px solid #29394f; background:#172335; }
.sidebar-logo-link { display:flex; align-items:center; justify-content:flex-start; gap:12px; width:100%; height:100%; color:#fff; text-decoration:none; }
.brand-symbol { display:flex; align-items:center; justify-content:center; flex:0 0 38px; width:38px; height:38px; }
.brand-mark { display:block; width:38px; height:38px; }
.brand-copy { display:flex; min-width:0; flex-direction:column; gap:3px; }
.sidebar-title { margin:0; color:#f3f7fc; font-size:15px; font-weight:650; letter-spacing:.02em; line-height:18px; white-space:nowrap; }
.brand-caption { color:#8fa1b8; font-size:9px; font-weight:600; letter-spacing:.12em; line-height:12px; white-space:nowrap; }
.collapse .sidebar-logo-link { gap:0; }
</style>
