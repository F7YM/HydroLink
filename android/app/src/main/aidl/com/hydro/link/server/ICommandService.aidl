// ICommandService.aidl
package com.hydro.link.server;

interface ICommandService {
    void destroy() = 16777114;
    String exec(String cmd) = 1;
}
