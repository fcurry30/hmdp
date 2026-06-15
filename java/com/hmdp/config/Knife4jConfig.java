package com.hmdp.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2WebMvc;

@Configuration
@EnableSwagger2WebMvc
public class Knife4jConfig {

    @Bean
    public Docket docket() {
        return new Docket(DocumentationType.SWAGGER_2)
                // 接口文档基本信息
                .apiInfo(apiInfo())
                // 开始选择需要生成文档的接口
                .select()
                // 扫描黑马点评的Controller包
                .apis(RequestHandlerSelectors.basePackage("com.hmdp.controller"))
                // 所有路径都生成文档
                .paths(PathSelectors.any())
                .build();
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("裤衩点评接口文档")
                .description("黑马点评项目后端接口说明")
                .version("3.0")
                .build();
    }
}