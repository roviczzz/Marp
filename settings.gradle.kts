rootProject.name = "Marp"

if (gradle.parent == null && file("../LetItRoll").exists()) {
    includeBuild("../LetItRoll")
}
