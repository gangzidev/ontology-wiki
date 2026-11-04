import defaultSettings from '@/settings'
import { useDynamicTitle } from '@/utils/dynamicTitle'

const { sideTheme, showSettings, navType, tagsView, tagsIcon, fixedHeader, sidebarLogo, dynamicTitle, footerVisible, footerContent } = defaultSettings

const storageSetting = JSON.parse(localStorage.getItem('layout-setting')) || ''
// Move users off the old RuoYi-style left navigation after this layout redesign.
const state = {
  title: '',
  theme: storageSetting.theme || '#0075DE',
  sideTheme: storageSetting.sideTheme === 'theme-light' ? sideTheme : (storageSetting.sideTheme || sideTheme),
  showSettings: showSettings,
  navType: storageSetting.navType === undefined || storageSetting.navType === 1 ? navType : storageSetting.navType,
  tagsView: storageSetting.tagsView === undefined ? tagsView : storageSetting.tagsView,
  tagsIcon: storageSetting.tagsIcon === undefined || storageSetting.tagsIcon === false ? tagsIcon : storageSetting.tagsIcon,
  fixedHeader: storageSetting.fixedHeader === undefined ? fixedHeader : storageSetting.fixedHeader,
  sidebarLogo: storageSetting.sidebarLogo === undefined ? sidebarLogo : storageSetting.sidebarLogo,
  dynamicTitle: storageSetting.dynamicTitle === undefined ? dynamicTitle : storageSetting.dynamicTitle,
  footerVisible: storageSetting.footerVisible === undefined ? footerVisible : storageSetting.footerVisible,
  footerContent: footerContent
}
const mutations = {
  CHANGE_SETTING: (state, { key, value }) => {
    if (state.hasOwnProperty(key)) {
      state[key] = value
    }
  },
  SET_TITLE: (state, title) => {
    state.title = title
  }
}

const actions = {
  // 修改布局设置
  changeSetting({ commit }, data) {
    commit('CHANGE_SETTING', data)
  },
  // 设置网页标题
  setTitle({ commit }, title) {
    commit('SET_TITLE', title)
    useDynamicTitle()
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}

