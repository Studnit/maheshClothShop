package com.maheshclothes.common;

public class NotFoundException extends BusinessException {

    public NotFoundException(String what) {
        super("error.notfound", what);
    }
}
