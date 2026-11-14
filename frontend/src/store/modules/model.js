import { getWikiStatus } from '@/api/ontology'

const state = {
  enabled: false,
  connected: false,
  loaded: false
}

const mutations = {
  SET_STATUS(state, { enabled, connected }) {
    state.enabled = !!enabled
    state.connected = !!connected
    state.loaded = true
  }
}

const actions = {
  fetchStatus({ commit }) {
    return getWikiStatus()
      .then(res => {
        const data = res.data || {}
        commit('SET_STATUS', {
          enabled: data.enabled,
          connected: data.connected
        })
      })
      .catch(() => {
        // 接口异常时保守判断为未配置，不阻断页面渲染
        commit('SET_STATUS', { enabled: false, connected: false })
      })
  }
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}
