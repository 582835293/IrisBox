package com.irisbox.viewer;

/** Only public ordinals enter UI labels. Permanent internal IDs select data. */
final class InstanceNames {
    private InstanceNames() {}
    static boolean valid(int number) { return number>=1&&number<=65529; }
    static String title(int number) {
        if(!valid(number))throw new IllegalArgumentException("Invalid display number");
        return I18n.s(R.string.ui_1150ec9bec2f)+number;
    }
    static String description(String action,int number) {
        if(!valid(number))throw new IllegalArgumentException("Invalid display number");
        return action+"-"+number;
    }
}
