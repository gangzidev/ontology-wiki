package com.ontologywiki;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * 启动程序
 * 
 * @author ontologywiki
 */
@SpringBootApplication(exclude = { DataSourceAutoConfiguration.class })
public class OntologyWikiApplication
{
    public static void main(String[] args)
    {
        SpringApplication.run(OntologyWikiApplication.class, args);
        System.out.println("(♥◠‿◠)ﾉﾞ  数智本体 Wiki启动成功   ლ(´ڡ`ლ)ﾞ  \n");
    }
}
