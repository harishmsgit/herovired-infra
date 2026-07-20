def readCommonEnv(script, String path = 'herovired-infra/config/common.env') {
  if (!script.fileExists(path)) {
    return [:]
  }

  def values = [:]
  script.readFile(file: path).eachLine { line ->
    def trimmed = line.trim()
    if (!trimmed || trimmed.startsWith('#')) {
      return
    }

    def separator = trimmed.indexOf('=')
    if (separator <= 0) {
      return
    }

    def key = trimmed.substring(0, separator).trim()
    def value = trimmed.substring(separator + 1).trim()
    values[key] = value
  }
  return values
}

def resolveConfigValue(script, Map sharedConfig, String key, String legacyDefault = '') {
  def explicit = script.params[key]
  if (explicit != null) {
    explicit = explicit.toString().trim()
  }

  def sharedValue = sharedConfig[key]
  if (sharedValue != null) {
    sharedValue = sharedValue.toString().trim()
  }

  if (explicit && explicit != legacyDefault) {
    return explicit
  }

  if (sharedValue) {
    return sharedValue
  }

  if (explicit) {
    return explicit
  }

  return legacyDefault
}

return this
