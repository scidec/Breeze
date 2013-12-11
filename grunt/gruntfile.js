module.exports = function(grunt) {

  var path = require('path');
  
  var msBuild = 'C:/Windows/Microsoft.NET/Framework/v4.0.30319/MSBuild.exe ';
  var msBuildOptions = ' /p:Configuration=Release /verbosity:minimal ';
  
  var samplesDir = '../Samples/';
  var tempDir = '../_temp2/';
  var solutionNames = [
           'DocCode',
           'ToDo',
           'ToDo-Angular',
           'ToDo-AngularWithDI',
           'ToDo-Require',
           'NoDb',
           'CarBones',
           'Edmunds',
           'TempHire',
        ];
  var solutionFileNames = solutionNames.map(function(sn) {
    return samplesDir + sn + '/' + sn + '.sln';
  });
  var solutionDirs = solutionNames.map(function(sn) {
    return samplesDir + sn + '/';
  });
  
    
  var versionNum = getBreezeVersion();
  var zipFileName = '../breeze-runtime-' + versionNum + '.zip';
  var zipPlusFileName = '../breeze-runtime-plus-' + versionNum + '.zip';
  grunt.log.writeln('zipName: ' + zipPlusFileName);
  grunt.file.write(tempDir + 'version.txt', 'Version: ' + versionNum);
  
  var nugetPackageNames = [
     'Breeze.WebApi', 
	   'Breeze.WebApi2.EF6',
	   'Breeze.Client',
	   'Breeze.Server.WebApi2',
     'Breeze.Server.ContextProvider.EF6',
     'Breeze.Server.ContextProvider'
	];
  
  var tempPaths = [
     'bin','obj', 'packages','*_Resharper*','*.suo'
  ];
  
  
  var nuPackNames = 'Breeze.WebApi, Breeze.WebApi2.EF6'
	 
  // Project configuration.
  grunt.initConfig({
    pkg: grunt.file.readJSON('package.json'),
  	solutionNames: solutionNames,
    solutionDirs: solutionDirs,
	  msBuild: {
      source: {
        msBuildOptions: msBuildOptions,
        solutionFileNames: ['../Breeze-Build.sln']
      },
      samples: {
        msBuildOptions: msBuildOptions,
        solutionFileNames: solutionFileNames,
      },
    },
    clean: {
      options: {
        // "no-write": true,
        force: true,
      },
      samplePackages: ['../Samples/**/packages'],  
      samples:  join(solutionDirs, tempPaths)
    },
    copy: {
      preZip: {
        files: [ 
          { expand: true, cwd: '../Breeze.Client', src: ['Scripts/breeze*.js'], dest: tempDir },
          { expand: true, cwd: '../Breeze.Client/Scripts/IBlade', src: ['b??_breeze.**js'], dest: tempDir + 'Scripts/Adapters/', 
            rename: function(dest, src) {
              return dest + 'breeze' + src.substring(src.indexOf('.'));
            }
          },
          { expand: true, cwd: '../Breeze.Client/Scripts/ThirdParty', src: ['q.**js'], dest: tempDir + 'Scripts' },
          { expand: true, cwd: '../Breeze.Client/Typescript', src: ['Typescript/breeze.d.ts'], dest: tempDir },
          { expand: true, cwd: '../Breeze.Client', src: ['Metadata/*.*'], dest: tempDir },
          
          { expand: true, cwd: '../Breeze.WebApi', src: ['Breeze.WebApi.dll'], dest: tempDir + 'Server'},
          { expand: true, cwd: '../Breeze.WebApi.EF', src: ['Breeze.WebApi.EF.dll'], dest: tempDir + 'Server'},
          { expand: true, cwd: '../Breeze.WebApi.NH', src: ['Breeze.WebApi.NH.dll'], dest: tempDir + 'Server'},
          
          { expand: true, cwd: '../Breeze.WebApi2', src: ['Breeze.WebApi2.dll'], dest: tempDir + 'Server'},
          { expand: true, cwd: '../Breeze.ContextProvider', src: ['Breeze.ContextProvider.dll'], dest: tempDir + 'Server'},
          { expand: true, cwd: '../Breeze.ContextProvider.EF6', src: ['Breeze.ContextProvider.EF6.dll'], dest: tempDir + 'Server'},
          
          { expand: true, cwd: '..', src: ['readme.txt'], dest: tempDir },
          buildSampleCopy('../', tempDir , 'DocCode', ['**/Todos.sdf']),
          buildSampleCopy('../', tempDir , 'ToDo', ['**/*.sdf']),
          buildSampleCopy('../', tempDir , 'ToDo-Angular', ['**/*.sdf']),
          buildSampleCopy('../', tempDir , 'ToDo-AngularWithDI', ['**/*.sdf']),
          buildSampleCopy('../', tempDir , 'ToDo-Require', ['**/*.sdf']),
          buildSampleCopy('../', tempDir , 'NoDb'),
          buildSampleCopy('../', tempDir , 'Edmunds'),
          buildSampleCopy('../', tempDir , 'TempHire'),
          buildSampleCopy('../', tempDir , 'CarBones', ['**/*.mdf', '**/*.ldf'])
        ]
      },  
    },
    compress: {
      base: {
        options: {
          archive:  zipFileName,
          mode: 'zip',
          level: 9,
          
        },
        files: [ 
          { expand: true, cwd: tempDir, src: [ '**/**', '!Samples/**/*' ], dest: '/' } 
        ]
      },
      baseWithSamples: {
        options: {
          archive:  zipPlusFileName,
          mode: 'zip',
          level: 9
        },
        files: [ 
          { expand: true, dot: true, cwd: tempDir, src: [ '**/*' ], dest: '/' } 
        ]
      }
    },
    nugetUpdate: {
      samples: {
        solutionFileNames: solutionFileNames
      }
    },
    prepareSample: {
      samples: {
        solutionFileNames: solutionFileNames
      }
    },
    listFiles: {
      samples: {
        src: ['../Samples/**/packages.config']
      }
    },
   
  });


  grunt.loadNpmTasks('grunt-exec');
  grunt.loadNpmTasks('grunt-contrib-copy');
  grunt.loadNpmTasks('grunt-contrib-clean');
  grunt.loadNpmTasks('grunt-contrib-compress');
  
  grunt.registerMultiTask('nugetUpdate', 'nuget update', function( ) {
    
    // dynamically build the exec tasks
    grunt.log.writeln('target: ' + this.target);
    var that = this;
    
    this.data.solutionFileNames.forEach(function(solutionFileName) {
      execNugetInstall(solutionFileName, that.data);
      execNugetUpdate(solutionFileName, nugetPackageNames, that.data);
    });
  });
   
  grunt.registerMultiTask('msBuild', 'Execute MsBuild', function( ) {
    // dynamically build the exec tasks
    grunt.log.writeln('target: ' + this.target);
    grunt.log.writeln('msBuildOptions: ' + this.data.msBuildOptions);
    var that = this;
    
    this.data.solutionFileNames.forEach(function(solutionFileName) {
      execMsBuild(solutionFileName, that.data);
    });
    
  });  
  
  // for debugging file patterns
  grunt.registerMultiTask('listFiles', 'List files', function() {
    grunt.log.writeln('target: ' + this.target);
    
    this.files.forEach(function(fileGroup) {
      fileGroup.src.forEach(function(fileName) {
        grunt.log.writeln('file: ' + fileName);
      });
    });
  });

  grunt.registerTask('buildRelease', ['msBuild:source', 'nugetUpdate', 'clean:samplePackages', 'msBuild:samples']);
  grunt.registerTask('packageRelease', [ 'clean:samples', 'copy:preZip', 'compress']);     
  grunt.registerTask('default', ['buildRelease', 'packageRelease']);
  
  function getBreezeVersion() {
     var versionFile = grunt.file.read('../Breeze.Client/Scripts/IBlade/_head.jsfrag');    
     var regex = /\s+version:\s*"(\d.\d\d*.?\d*)"/
     var matches = regex.exec(versionFile);
     
     if (matches == null) {
        throw new Error('Version number not found');
     }
     // matches[0] is entire version string - [1] is just the capturing group.
     var versionNum = matches[1];
     grunt.log.writeln('version: ' + versionNum);
     return versionNum;
  }
  
  function join(a1, a2) {
    var result = [];
    a1.forEach(function(a1Item) {
      a2.forEach(function(a2Item) {
        result.push(a1Item + '**/' + a2Item);
      });
    });
    return result;
  }
  
  function buildSampleCopy(srcRoot, destRoot, sampleName, patternsToExclude) {
    var files = ['**/*', '**/.nuget/*'];
    if (patternsToExclude) {
      patternsToExclude.forEach(function(pattern) {   
        files.push('!' + pattern);
      });
    }
    var cmd = { 
      expand: true, 
      cwd: srcRoot + 'Samples/' + sampleName, 
      src: files,
      dest: destRoot + 'Samples/' + sampleName,
    }
    return cmd;
  }
  
  function execNugetInstall(solutionFileName, config ) {
    
    var solutionDir = path.dirname(solutionFileName);
    var packagesDir = solutionDir + '/packages';

    var configFileNames = grunt.file.expand(solutionDir + '/**/packages.config');
    configFileNames.forEach(function(fn) {
      grunt.log.writeln('Preparing nuget install for file: ' + fn);
      var cmd = 'nuget install ' + fn + ' -OutputDirectory ' + packagesDir;
      // grunt.log.writeln('cmd: ' + cmd);
      runExec('nugetInstall', {
        cmd: cmd
      });
    });
  }
  
  function execNugetUpdate(solutionFileName, nugetPackageNames, config) {
    var baseCmd = 'nuget update ' + solutionFileName + ' -Id ';
    
    nugetPackageNames.forEach(function(npn) {
      runExec('nugetUpdate', {
        cmd: baseCmd + npn
      });
    });
  }

  function execMsBuild(solutionFileName, config ) {
    grunt.log.writeln('Executing solution build for: ' + solutionFileName);
    
    var cwd = path.dirname(solutionFileName);
    var baseName = path.basename(solutionFileName);
    var rootCmd = msBuild + '"' + baseName +'"' + config.msBuildOptions + ' /t:' 
    
    runExec('msBuildClean', {
      cwd: cwd,
      cmd: rootCmd + 'Clean'
    });
    runExec('msBuildRebuild', {
      cwd: cwd,
      cmd: rootCmd + 'Rebuild'
    });

  }
  
  var index = 0;
  
  function runExec(name, config) {
    var name = name+'-'+index++;
    grunt.config('exec.' + name, config);
    grunt.task.run('exec:' + name);
  }
  
  function log(err, stdout, stderr, cb) {
    if (err) {
      grunt.log.write(err);
      grunt.log.write(stderr);
      throw new Error("Failed");
    }

    grunt.log.write(stdout);

    cb();
  }


};