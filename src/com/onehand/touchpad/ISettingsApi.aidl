package com.onehand.touchpad;

interface ISettingsApi {
    boolean putSecure(String key, String value);
    void destroy();
}
