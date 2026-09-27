package si.safeer.tv.link
object GlobalMeshTest {
 @JvmStatic fun main(args:Array<String>) {
  fun p(local:Boolean=false,direct:Boolean=false,relay:Boolean=false,paired:Boolean=true,key:String="fp")=
    MeshPeer("peer",paired,key,local,direct,relay)
  check(MeshPolicy(true,true).route(p(local=true,direct=true,relay=true))==MeshReachability.LOCAL)
  check(MeshPolicy(true,true).route(p(direct=true,relay=true))==MeshReachability.DIRECT_INTERNET)
  check(MeshPolicy(true,true).route(p(relay=true))==MeshReachability.RELAY)
  check(MeshPolicy(false,true).route(p(relay=true))==MeshReachability.OFFLINE)
  check(MeshPolicy(true,true).route(p(relay=true,paired=false))==MeshReachability.OFFLINE)
  check(MeshPolicy(true,true).route(p(relay=true,key=""))==MeshReachability.OFFLINE)
  println("GlobalMeshTest OK")
 }
}
