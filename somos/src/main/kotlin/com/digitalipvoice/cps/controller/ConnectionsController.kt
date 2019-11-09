package com.digitalipvoice.cps.controller

import com.digitalipvoice.cps.client.somos.models.SMSConnectionDTO
import com.digitalipvoice.cps.components.SMSConnectionManager
import com.digitalipvoice.cps.exceptions.BadRequestException
import com.digitalipvoice.cps.model.AppUser
import com.digitalipvoice.cps.persistance.model.Privilege
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.ResponseBody

/**
 * Controller class that has local control method.
 * Method in this controller will be called by admin module and requires no authentication.
 * Require host ip to be 127.0.0.1
 */
@Controller
class ConnectionsController{
    @Autowired
    private lateinit var smsConnectionManager: SMSConnectionManager


    @GetMapping("/connections/restart")
    @ResponseBody
    @PreAuthorize("isMgiEnabled()")
    fun restartConnections(user:AppUser): ResponseEntity<String>{
        if (!user.isSuperAdmin){
            throw BadRequestException("You should be super admin to execute this command")
        }
        smsConnectionManager.restartConnections()
        return ResponseEntity.ok("")
    }

    /**
     * This is called from admin module.
     */
    @GetMapping("/connections/stop")
    @ResponseBody
    @PreAuthorize("isMgiEnabled()")
    fun stopConnections(user:AppUser): ResponseEntity<String>{
        if (!user.isSuperAdmin){
            throw BadRequestException("You should be super admin to execute this command")
        }
        smsConnectionManager.stopConnections()
        return ResponseEntity.ok("")
    }

    @GetMapping("/connections")
    @ResponseBody
    @PreAuthorize("isMgiEnabled() AND hasAuthority('${Privilege.ReadSmsConnections}')")
    fun connections(): ResponseEntity<List<SMSConnectionDTO>> {
        return ResponseEntity.ok(smsConnectionManager.getConnectionsDTO())
    }
}