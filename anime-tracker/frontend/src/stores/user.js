import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export const useUserStore = defineStore('user', () => {
  const user = ref(JSON.parse(localStorage.getItem('anime_user') || 'null'))
  const loggedIn = computed(() => user.value !== null && user.value.token)

  function setUser(data) {
    user.value = data
    localStorage.setItem('anime_user', JSON.stringify(data))
  }

  function logout() {
    user.value = null
    localStorage.removeItem('anime_user')
  }

  const token = computed(() => user.value?.token || null)

  return { user, loggedIn, token, setUser, logout }
})
