<template>
  <div>
    <svg-icon icon-class="question" @click="goto" />
  </div>
</template>

<script>
export default {
  name: 'OntologyWikiDoc',
  data() {
    return {
      url: '/docs'
    }
  },
  methods: {
    goto() {
      window.open(this.url)
    }
  }
}
</script>