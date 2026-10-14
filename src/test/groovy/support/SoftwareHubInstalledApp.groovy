package support

class SoftwareHubInstalledApp extends TestChildApp {
    Map preferences

    @Override void updateSetting(String name, Map setting) {
        super.updateSetting(name, setting)
        preferences[name] = setting.value
    }
}
