<template>
  <el-menu class="topbar-menu" :default-active="activeMenu" :active-text-color="theme" mode="horizontal">
    <!-- 平铺入口：首页、工作台 -->
    <sidebar-item v-for="route in primaryMenus" :key="route.path" :item="route" :base-path="route.path" />
    <!-- 分组入口：完全由数据库菜单层级决定（知识中心 / 本体工程 / AI 服务 / 平台运维） -->
    <el-submenu v-for="group in navGroups" :key="group.key" :index="group.key" :class="{ 'is-active': isGroupActive(group) }">
      <template slot="title">
        <item :icon="group.icon" :title="group.title" />
      </template>
      <sidebar-item v-for="(route, index) in group.items" :key="route.path + index" :item="route" :base-path="route.path" />
    </el-submenu>
  </el-menu>
</template>

<script>
import path from 'path'
import SidebarItem from '../Sidebar/SidebarItem'
import Item from '../Sidebar/Item'

/**
 * 需要平铺到顶部、不放进下拉分组的入口。
 * 只保留这两个：首页来自前端常量路由，工作台是单页菜单，
 * 其余分组一律按 sys_menu 的 parent_id 层级自动生成。
 */
const PRIMARY_TITLES = ['首页', '工作台']

export default {
  components: { SidebarItem, Item },
  computed: {
    theme() {
      return this.$store.state.settings.theme
    },
    /** 顶层路由（已过滤隐藏项） */
    topRoutes() {
      return (this.$store.state.permission.sidebarRouters || []).filter(route => !route.hidden)
    },
    /** 把路由树翻译成「平铺入口 + 下拉分组」 */
    navModel() {
      const flatItems = []
      const groups = []

      this.topRoutes.forEach(route => {
        const children = (route.children || []).filter(child => !child.hidden)
        const isRootWrapper = route.path === '' || route.path === '/'

        // 根包装路由（首页所在的 Layout 壳）直接把子项平铺出来
        if (isRootWrapper) {
          children.forEach(child => flatItems.push(this.toFlatItem(child, route.path)))
          return
        }
        // 没有子项的一级菜单（工作台）直接平铺
        if (!children.length) {
          flatItems.push(this.toFlatItem(route, '/'))
          return
        }
        groups.push({
          key: route.path,
          title: (route.meta && route.meta.title) || '',
          icon: route.meta && route.meta.icon,
          activePaths: children.map(child => path.resolve(route.path || '/', child.path || '')),
          items: children.map(child => ({
            ...child,
            path: path.resolve(route.path || '/', child.path || '')
          }))
        })
      })

      // 分组内的单页若属于平铺白名单，提升到顶部并移出分组
      groups.forEach(group => {
        group.items = group.items.filter(item => {
          const title = item.meta && item.meta.title
          if (PRIMARY_TITLES.includes(title)) {
            flatItems.push(this.toFlatItem(item, '/'))
            return false
          }
          return true
        })
      })

      // 平铺入口按白名单顺序排列，保证 首页 → 工作台
      flatItems.sort((a, b) => {
        const ai = PRIMARY_TITLES.indexOf(a.title)
        const bi = PRIMARY_TITLES.indexOf(b.title)
        return (ai === -1 ? 99 : ai) - (bi === -1 ? 99 : bi)
      })

      return { flatItems, groups: groups.filter(group => group.items.length) }
    },
    primaryMenus() {
      return this.navModel.flatItems.map(entry => entry.route)
    },
    navGroups() {
      return this.navModel.groups
    },
    activeMenu() {
      const { meta, path } = this.$route
      if (meta.activeMenu) {
        return meta.activeMenu
      }
      return path
    }
  },
  methods: {
    /** 包装成平铺入口，路径统一为绝对路径 */
    toFlatItem(route, basePath) {
      return {
        title: (route.meta && route.meta.title) || '',
        route: { ...route, path: path.resolve(basePath || '/', route.path || '') }
      }
    },
    /** 当前路由是否落在该分组内，用于高亮 */
    isGroupActive(group) {
      const current = this.$route.path
      return group.activePaths.some(p => current === p || current.indexOf(p + '/') === 0)
    }
  }
}
</script>

<style lang="scss">
.topbar-menu.el-menu--horizontal {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: stretch;
  height: 64px;
  overflow-x: auto;
  overflow-y: hidden;
  white-space: nowrap;
  border-bottom: 0;
  background: transparent;
}

.topbar-menu.el-menu--horizontal::-webkit-scrollbar { display: none; }
.topbar-menu.el-menu--horizontal > .el-menu-item,
.topbar-menu.el-menu--horizontal > .el-submenu {
  float: none;
  flex-shrink: 0;
}

.topbar-menu.el-menu--horizontal > .el-menu-item,
.topbar-menu.el-menu--horizontal > .el-submenu > .el-submenu__title {
  height: 64px !important;
  line-height: 64px !important;
  padding: 0 16px !important;
  color: #5f6b7a !important;
  border-bottom: 2px solid transparent;
}

.topbar-menu.el-menu--horizontal > .el-menu-item:hover,
.topbar-menu.el-menu--horizontal > .el-submenu > .el-submenu__title:hover {
  color: #1d2330 !important;
  background: #f5f8fc !important;
}

.topbar-menu.el-menu--horizontal > .el-menu-item.is-active,
.topbar-menu.el-menu--horizontal > .el-submenu.is-active > .el-submenu__title {
  color: #2f88ff !important;
  border-bottom-color: #2f88ff !important;
  background: transparent !important;
  font-weight: 600;
}

.topbar-menu .svg-icon,
.topbar-menu .menu-entry-icon { margin-right: 8px; }
.topbar-menu .el-submenu .el-menu { min-width: 220px; max-height: calc(100vh - 86px); overflow-y: auto; padding: 8px; border: 1px solid #e5ebf3; border-radius: 10px; box-shadow: 0 12px 28px rgba(30, 55, 90, .14); }
.topbar-menu .el-submenu .el-menu-item,
.topbar-menu .el-submenu .el-submenu__title { height: 38px; line-height: 38px; border-radius: 7px; }
</style>
