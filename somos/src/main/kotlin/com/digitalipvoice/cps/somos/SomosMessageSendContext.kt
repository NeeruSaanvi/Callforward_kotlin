package com.digitalipvoice.cps.somos

import com.digitalipvoice.cps.persistance.model.SMSMessage
import com.digitalipvoice.cps.somos.message.Y1DcmMsg

class SomosMessageSendContext(
        val message:SMSMessage,
        val sentHandler:(sent:Y1DcmMsg) -> Unit,

        // When message is arrived from somos mgi server and need to quick response
        val receiveHandler1:((message:String) -> Any)?,

        // No quick response, do after all database operation has done.
        val receiveHandler2: ((messageId: String) -> Any)?
        )
