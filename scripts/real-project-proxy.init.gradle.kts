// Registers the `realProjectProxy` JavaExec task for scripts/real-project-proxy.sh without
// touching build.gradle.kts: the task only exists while this init script is passed with -I.
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer

gradle.allprojects {
    afterEvaluate {
        val sourceSets = extensions.findByType(SourceSetContainer::class.java) ?: return@afterEvaluate
        val realProject = sourceSets.findByName("realProject") ?: return@afterEvaluate
        tasks.register("realProjectProxy", JavaExec::class.java) {
            group = "verification"
            description = "Runs the reflecting proxy of the real-project test source set"
            classpath = realProject.runtimeClasspath
            mainClass.set("com.github.yakov255.perlinecoverageinfo.realproject.proxy.ReflectingProxyKt")
        }
    }
}
