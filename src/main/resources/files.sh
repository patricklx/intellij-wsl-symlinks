while IFS='$\n' read line
do
  IFS=';'
  read -ra command <<< "$line"
  type=${command[0]}
  value=${command[1]}
  if [ "$type" == "is-symlink" ]; then
    (test -L "$value") && echo "true" || echo "false"
  fi
  if [ "$type" == "read-symlink" ]; then
    readlink -f "$value"
  fi
done