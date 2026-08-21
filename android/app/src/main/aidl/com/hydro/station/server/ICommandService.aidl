// ICommandService.aidl
package com.hydro.station.server;

interface ICommandService {
    void destroy() = 16777114;
    String exec(String cmd) = 1;
}
