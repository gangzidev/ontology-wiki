<template>
  <div class="sidebar-logo-container" :class="{ collapse }">
    <transition name="sidebarLogoFade">
      <router-link to="/" class="sidebar-logo-link">
        <span class="brand-symbol" aria-hidden="true">
          <svg viewBox="0 0 40 40" class="brand-mark">
            <path d="M11 27 19.5 12 29 27M11 27h18" />
            <circle cx="11" cy="27" r="3.2" />
            <circle cx="19.5" cy="12" r="3.2" />
            <circle cx="29" cy="27" r="3.2" />
            <circle cx="20" cy="27" r="2.4" />
          </svg>
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
.brand-symbol { display:flex; align-items:center; justify-content:center; flex:0 0 38px; width:38px; height:38px; border:1px solid rgba(255,255,255,.18); border-radius:12px; background:linear-gradient(145deg,#397ff0,#2861d7); color:#fff; box-shadow:0 6px 16px rgba(21,91,212,.28); }
.brand-mark { width:30px; height:30px; fill:none; stroke:currentColor; stroke-width:1.8; stroke-linecap:round; stroke-linejoin:round; }
.brand-mark circle { fill:currentColor; stroke:none; }
.brand-copy { display:flex; min-width:0; flex-direction:column; gap:3px; }
.sidebar-title { margin:0; color:#f3f7fc; font-size:15px; font-weight:650; letter-spacing:.02em; line-height:18px; white-space:nowrap; }
.brand-caption { color:#8fa1b8; font-size:9px; font-weight:600; letter-spacing:.12em; line-height:12px; white-space:nowrap; }
.collapse .sidebar-logo-link { gap:0; }
</style>
