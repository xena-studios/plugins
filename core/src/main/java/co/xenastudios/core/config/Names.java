package co.xenastudios.core.config;

/** Setting names: a record component {@code permissionOthers} is the key {@code permission-others}. */
final class Names {

    private Names() {}

    static String key(String componentName) {
        StringBuilder key = new StringBuilder(componentName.length() + 4);
        for (int i = 0; i < componentName.length(); i++) {
            char c = componentName.charAt(i);
            if (Character.isUpperCase(c)) {
                key.append('-').append(Character.toLowerCase(c));
            } else {
                key.append(c);
            }
        }
        return key.toString();
    }
}
