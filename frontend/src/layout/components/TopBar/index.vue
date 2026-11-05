<template>
  <el-menu class="topbar-menu" :default-active="activeMenu" :active-text-color="theme" mode="horizontal">
    <sidebar-item :key="route.path + index" v-for="(route, index) in primaryMenus" :item="route" :base-path="route.path" />
    <el-submenu v-for="group in featuredGroups" :key="group.key" :index="'featured-' + group.key" class="featured-menu" :class="{ 'is-active': isGroupActive(group) }">
      <template slot="title"><i :class="group.icon"></i><span>{{ group.title }}</span><small>{{ group.items.length }}</small></template>
      <sidebar-item :key="route.path + index" v-for="(route, index) in group.items" :item="route" :base-path="route.path" />
    </el-submenu>
    <el-submenu v-if="moreMenus.length" index="more" class="more-menu" :class="{ 'is-active': moreActive }">
      <template slot="title"><i class="el-icon-more"></i><span>更多</span></template>
      <el-submenu v-for="group in moreGroups" :key="group.key" :index="'more-' + group.key" class="more-group">
        <template slot="title"><i :class="group.icon"></i><span>{{ group.title }}</span><small>{{ group.items.length }}</small></template>
        <sidebar-item :key="route.path + index" v-for="(route, index) in group.items" :item="route" :base-path="route.path" />
      </el-submenu>
    </el-submenu>
  </el-menu>
</template>

<script>
import path from 'path'
import SidebarItem from '../Sidebar/SidebarItem'

export default {
  components: { SidebarItem },
  computed: {
    theme() {
      return this.$store.state.settings.theme
    },
    topMenus() {
      return this.$store.state.permission.sidebarRouters
        .filter(route => !route.hidden)
        .reduce((menus, route) => {
          const children = (route.children || []).filter(child => !child.hidden)
          if (!children.length) return menus.concat(route)
          return menus.concat(children.map(child => ({
            ...child,
            path: path.resolve(route.path || '/', child.path || '')
          })))
        }, [])
    },
    primaryMenus() {
      const preferred = ['首页', '工作台', 'Wiki资产', '数据源管理', '分析任务']
      return preferred.map(title => this.topMenus.find(route => route.meta && route.meta.title === title)).filter(Boolean)
    },
    secondaryGroups() {
      const primaryPaths = this.primaryMenus.map(route => route.path)
      const groups = [
        { key: 'ontology', title: '本体配置', icon: 'el-icon-set-up', items: [] },
        { key: 'knowledge', title: '知识资产', icon: 'el-icon-collection', items: [] },
        { key: 'system', title: '系统管理', icon: 'el-icon-setting', items: [] },
        { key: 'other', title: '其他功能', icon: 'el-icon-more', items: [] }
      ]
      this.topMenus.filter(route => !primaryPaths.includes(route.path)).forEach(route => {
        const title = route.meta && route.meta.title
        const group = ['配置草案', '导入记录'].includes(title)
          ? groups[0]
          : ['Wiki空间', '行业知识空间', '业务知识问答'].includes(title)
            ? groups[1]
            : /用户|角色|菜单|部门|岗位|字典|参数|通知|日志|监控|定时|代码生成|服务监控|在线用户/.test(title || '')
              ? groups[2]
              : groups[3]
        group.items.push(route)
      })
      return groups.filter(group => group.items.length)
    },
    featuredGroups() {
      return this.secondaryGroups.filter(group => ['ontology', 'knowledge'].includes(group.key))
    },
    moreGroups() {
      return this.secondaryGroups.filter(group => !['ontology', 'knowledge'].includes(group.key))
    },
    moreMenus() {
      return this.moreGroups.reduce((routes, group) => routes.concat(group.items), [])
    },
    moreActive() {
      const current = this.$route.path
      return this.moreMenus.some(route => current === route.path || current.indexOf(route.path + '/') === 0)
    },
    // 默认激活的菜单
    activeMenu() {
      const { meta, path } = this.$route
      if (meta.activeMenu && !this.moreActive) {
        return meta.activeMenu
      }
      if (this.moreActive) return 'more'
      return path
    },
  },
  methods: {
    isGroupActive(group) {
      return group.items.some(route => this.$route.path === route.path || this.$route.path.indexOf(route.path + '/') === 0)
    }
  },
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
.topbar-menu .more-menu .el-menu,
.topbar-menu .featured-menu .el-menu { min-width: 220px; max-height: calc(100vh - 86px); overflow-y: auto; padding: 8px; border: 1px solid #e5ebf3; border-radius: 10px; box-shadow: 0 12px 28px rgba(30, 55, 90, .14); }
.topbar-menu .more-menu .el-menu-item,
.topbar-menu .featured-menu .el-menu-item,
.topbar-menu .more-menu .el-submenu__title { height: 38px; line-height: 38px; border-radius: 7px; }
.topbar-menu .more-menu .more-group > .el-submenu__title { color: #344054 !important; font-weight: 600; }
.topbar-menu .more-menu .more-group > .el-submenu__title small { float: right; min-width: 18px; height: 18px; margin-top: 10px; padding: 0 5px; color: #7c8a9f; background: #f1f4f8; border-radius: 9px; font-size: 11px; line-height: 18px; text-align: center; }
.topbar-menu .more-menu .more-group > .el-submenu__title .el-submenu__icon-arrow { right: 10px; }
.topbar-menu .featured-menu > .el-submenu__title small { display:inline-block; min-width:18px; height:18px; margin-left:7px; padding:0 5px; border-radius:9px; background:#f1f4f8; color:#7c8a9f; font-size:11px; line-height:18px; text-align:center; }
.topbar-menu .featured-menu > .el-submenu__title .el-submenu__icon-arrow { margin-top:-5px; }
</style>
