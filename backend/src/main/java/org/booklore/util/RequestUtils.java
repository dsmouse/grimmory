package org.booklore.util;


import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.experimental.UtilityClass;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@UtilityClass
public class RequestUtils {

    public HttpServletRequest getCurrentRequest() {
        return getAttrs().getRequest();
    }

    public HttpServletResponse getCurrentResponse() {
        HttpServletResponse response = getAttrs().getResponse();
        if (response == null) {
            throw new IllegalStateException("No current HTTP response found");
        }
        return response;
    }

    private ServletRequestAttributes getAttrs() {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            throw new IllegalStateException("No current HTTP request found");
        }
        return attrs;
    }
}
