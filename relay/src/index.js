export default {
  async fetch(request, env) {
    if (request.method === "OPTIONS") return new Response(null,{headers:cors()});
    if (!["GET","POST"].includes(request.method)) return reply({error:"method"},405);
    return env.PRESENCE.get(env.PRESENCE.idFromName("global")).fetch(request);
  }
};
function cors(){return {"Access-Control-Allow-Origin":"*","Access-Control-Allow-Methods":"GET,POST,OPTIONS","Access-Control-Allow-Headers":"Content-Type","Content-Type":"application/json"};}
function reply(data,status=200){return new Response(JSON.stringify(data),{status,headers:cors()});}
export class PresenceStoreDO {
  constructor(ctx,env){this.ctx=ctx;}
  async fetch(request){
    const u=new URL(request.url), s=this.ctx.storage;
    if(request.method==="POST" && u.pathname==="/key"){
      const b=await request.json();
      if(typeof b.id!=="string"||typeof b.key!=="string"||b.id.length>64||b.key.length>512) return reply({error:"invalid"},400);
      await s.put("key:"+b.id,b.key); return reply({ok:true});
    }
    if(request.method==="GET" && u.pathname==="/keys"){
      const all=await s.list({prefix:"key:"});
      return reply(Array.from(all,([k,v])=>({id:k.slice(4),key:v})));
    }
    if(request.method==="POST" && u.pathname==="/message"){
      const b=await request.json();
      if(typeof b.sender!=="string"||typeof b.recipient!=="string"||typeof b.ciphertext!=="string"||b.ciphertext.length>22000) return reply({error:"invalid"},400);
      if(!(await s.get("key:"+b.recipient))) return reply({error:"unknown recipient"},404);
      await s.put("msg:"+b.recipient+":"+b.sender,b.ciphertext);
      return reply({ok:true});
    }
    if(request.method==="GET" && u.pathname==="/messages"){
      const recipient=u.searchParams.get("recipient");
      if(!recipient||recipient.length>64) return reply({error:"recipient"},400);
      const all=await s.list({prefix:"msg:"+recipient+":"});
      return reply(Array.from(all,([k,v])=>({sender:k.slice(("msg:"+recipient+":").length),ciphertext:v})));
    }
    return reply({error:"not found"},404);
  }
}