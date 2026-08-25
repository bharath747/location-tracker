const {onDocumentCreated}=require('firebase-functions/v2/firestore');
const admin=require('firebase-admin');
admin.initializeApp();

exports.dispatchDeviceCommand=onDocumentCreated('devices/{deviceId}/commands/{commandId}', async event=>{
  const command=event.data.data();
  if(!command || command.status!=='PENDING') return;
  const {deviceId,commandId}=event.params;
  const commandRef=event.data.ref;
  const deviceRef=admin.firestore().collection('devices').doc(deviceId);
  const device=(await deviceRef.get()).data();
  const token=device?.fcmToken;
  if(!token){ await commandRef.update({status:'FAILED',error:'No FCM token'}); return; }
  try{
    const id=await admin.messaging().send({token,android:{priority:'high'},data:{action:String(command.action||''),commandId}});
    await commandRef.update({status:'SENT',fcmMessageId:id,sentAt:admin.firestore.FieldValue.serverTimestamp()});
  }catch(error){
    await commandRef.update({status:'FAILED',error:String(error.message||error)});
  }
});