pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KebbiMed"
include(":app")
// 凱比用到的後端 API（介面、資料類別、共用規則）。第三方後端看這個模組做 API
include(":api")
