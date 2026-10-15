package com.codingagent.storage.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.codingagent.storage.entity.Session;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SessionMapper extends BaseMapper<Session> {
}
