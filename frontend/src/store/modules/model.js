import { getWikiStatus } from '@/api/ontology'

const state = {
  enabled: false,
  loaded: false
}

const mutations = {
  SET_STATUS(state, { enabled }) {
    state.enabled = !!enabled
    state.loaded = true
  }
}

const actions = {
  fetchStatus({ commit }) {
    return getWikiStatus()
      .then(res => {
        commit('SET_STATUS', res.data || {})
      })
      .catch(() => {
        // 接口异常时保守判断为未配置，不阻断页面渲染
        commit('SET_STATUS', { enabled: false })
      })
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}
