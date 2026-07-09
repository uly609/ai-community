package com.ai.aicommunity.common;

import lombok.Data;

import lombok.Data;

@Data
public class Result<T> {

    private Integer code;

    private String message;

    private T data;


    public static <T> Result<T> success(T data){
        Result<T> result = new Result<>();
        result.code = ResultCode.SUCCESS;;
        result.message = "success";
        result.data = data;
        return result;
    }


    public static <T> Result<T> error(String message){
        Result<T> result = new Result<>();
        result.code = ResultCode.ERROR;
        result.message = message;
        return result;
    }
}