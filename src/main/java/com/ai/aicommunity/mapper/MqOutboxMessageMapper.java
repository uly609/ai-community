package com.ai.aicommunity.mapper;

import com.ai.aicommunity.entity.MqOutboxMessage;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MqOutboxMessageMapper extends BaseMapper<MqOutboxMessage> {
}
