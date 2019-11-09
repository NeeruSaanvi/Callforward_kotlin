package com.digitalipvoice.cps.configuration

import com.digitalipvoice.cps.components.MgiEnabledCheckSEHandler
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.security.access.expression.method.MethodSecurityExpressionHandler
import org.springframework.security.config.annotation.method.configuration.EnableGlobalMethodSecurity
import org.springframework.security.config.annotation.method.configuration.GlobalMethodSecurityConfiguration


@Configuration
@EnableGlobalMethodSecurity(prePostEnabled = true, securedEnabled = true)
class MethodSecurityConfig: GlobalMethodSecurityConfiguration(){
    @Value("\${mgi.enabled}")
    private val isMgiEnabled = false

    override fun createExpressionHandler(): MethodSecurityExpressionHandler  {
        return MgiEnabledCheckSEHandler(isMgiEnabled)
    }
}

