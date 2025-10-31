another question - can you create a prompt make-library-prompt.md that will instruct LLM to convert this
  application into a library so that many different bounded context can use same approach. with regards to
  SQL schemas make all tables part of messaging schema and allow consuming app to control configuration
  (pretty much evenrything that is in .env should be in library configuration). Library is meant to be
  consumed by other projects using micronaut. They will need to register their own handlers. Consider making
  information more rich for handler as additional info (command-id, idem, keys etc).