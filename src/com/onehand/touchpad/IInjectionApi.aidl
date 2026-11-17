package com.onehand.touchpad;

interface IInjectionApi {
    // action：InputEvent 的整数动作值；返回是否注入成功
    boolean send(int action, float x, float y, long downTime, long eventTime);
    void destroy();
    /** 杀掉同包名、PPID==1（已脱离 App）的 injection 兄弟进程，返回被杀数量。 */
    int killStaleSiblings();
}
