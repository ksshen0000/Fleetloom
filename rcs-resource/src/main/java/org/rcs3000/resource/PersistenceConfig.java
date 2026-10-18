package org.rcs3000.resource;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/** Kept out of {@link ResourceApplication} so that web slice tests start without a database. */
@Configuration(proxyBeanMethods = false)
@MapperScan(basePackages = "org.rcs3000.resource", annotationClass = Mapper.class)
class PersistenceConfig {
}
