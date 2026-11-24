import { execFileSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

export default function buildBackend() {
  const root = fileURLToPath(new URL('../../', import.meta.url))
  execFileSync(
    'java',
    [
      '-classpath',
      'gradle/wrapper/gradle-wrapper.jar',
      'org.gradle.wrapper.GradleWrapperMain',
      ':backend:app:installDist',
      ':backend:mock-comfy:installDist',
      '--quiet',
    ],
    { cwd: root, stdio: 'inherit' },
  )
}
