package example

object App {
  trait Alg[F[_], User, Tweet] {
    def postTweet(body: String): F[Option[Unit]]
    
    def getTweets(user: User): F[List[Tweet]]
    def getAllTweets: F[List[Tweet]]
    
    // Only get followees and followers of myself? Maybe cannot see from everyone?
    def getFollowees(user: User): F[List[User]]
    def getFollowers(user: User): F[List[User]]
  }
}
