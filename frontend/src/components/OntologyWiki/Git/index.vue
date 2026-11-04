<template>
  <div>
    <svg-icon icon-class="github" @click="goto" />
  </div>
</template>

<script>
export default {
  name: 'OntologyWikiGit',
  data() {
    return {
      url: '本项目代码仓库'
    }
  },
  methods: {
    goto() {
      window.open(this.url)
    }
  }
}
</script>